package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.application.port.SyncJobRepository;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.sync.SyncJob;
import com.cloudcost.domain.usage.CollectionWindows;
import com.cloudcost.domain.usage.CollectionWindows.TimeWindow;
import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.RefreshPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 조회 시점 수집 (캐시 방식).
 *
 * <ul>
 *   <li>인스턴스 정보: 마지막 수집 후 instanceTtl(10분)이 지났으면 DescribeInstances로 갱신
 *   <li>사용량: 마지막 CloudWatch 호출 후 usageTtl(1시간)이 지났을 때만 GetMetricStatistics 호출.
 *       그 안의 재조회는 DB에 저장된 값으로 응답한다 (고객 계정 CloudWatch 호출 최소화)
 *   <li>ERROR 연결: usageTtl마다 한 번 전체(AssumeRole·DescribeInstances·CloudWatch)를 다시 확인해 성공하면 ACTIVE로 복구한다.
 *       그 사이에는 AWS를 부르지 않고 저장된 데이터와 오류 원인을 보여 준다.
 * </ul>
 * 같은 계정을 동시에 조회해도 AWS는 한 번만 부르도록 계정 단위로 잠근다.
 *
 * <p>정합성: 시간별 사용량은 (계정, 인스턴스, 시각)으로 upsert하므로 같은 구간을 다시 읽어도 중복되지 않는다.
 * 인스턴스별 "수집 완료 시각(usageCollectedUntil)"은 성공한 구간의 끝까지만 앞으로 옮기고 실패한 구간은 넘지 않으므로,
 * 일부가 실패해도 성공한 부분은 남고 실패한 구간은 다음 수집에서 다시 읽는다.
 */
public class Ec2DataRefresher {

    private final AwsAccountRepository accounts;
    private final Ec2InstanceRepository instances;
    private final Ec2UsageRepository usage;
    private final SyncJobRepository syncJobs;
    private final CustomerAwsGateway gateway;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final UsageSettings settings;
    private final RefreshPolicy policy;
    private final AwsRetry retry;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Ec2DataRefresher(
        AwsAccountRepository accounts,
        Ec2InstanceRepository instances,
        Ec2UsageRepository usage,
        SyncJobRepository syncJobs,
        CustomerAwsGateway gateway,
        UnitOfWork unitOfWork,
        Clock clock,
        UsageSettings settings,
        Pauser pauser) {
        this.accounts = accounts;
        this.instances = instances;
        this.usage = usage;
        this.syncJobs = syncJobs;
        this.gateway = gateway;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.settings = settings;
        this.policy = settings.refreshPolicy();
        this.retry = new AwsRetry(pauser);
    }

    public Freshness refreshIfStale(String ownerId, UUID accountId) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, id -> new ReentrantLock());
        lock.lock();
        try {
            // 잠금을 기다리는 동안 다른 요청이 수집했을 수 있으므로 다시 읽는다.
            AwsAccount account = accounts.findByOwnerIdAndId(ownerId, accountId)
                .orElseThrow(AwsAccountService::notFound);
            return refresh(account);
        } finally {
            lock.unlock();
        }
    }

    private Freshness refresh(AwsAccount account) {
        Instant now = clock.instant();
        boolean needUsage = policy.usageStale(account.usageRefreshedAt(), now);
        // ERROR 연결은 1시간에 한 번만, 인스턴스와 사용량을 함께 다시 확인한다 (복구 판단을 한 번에 하기 위해).
        boolean needInstances = account.status() == AwsAccount.Status.ERROR
            ? needUsage
            : policy.instancesStale(account.instancesRefreshedAt(), now);
        if (!account.canCollect() || (!needInstances && !needUsage)) {
            return freshness(account, false, false, List.of());
        }

        SyncJob job = syncJobs.save(SyncJob.start(
            UUID.randomUUID(), account.ownerId(), account.id(), SyncJob.Trigger.QUERY, now));
        List<String> warnings = new ArrayList<>();
        boolean instancesDone = false;
        boolean usageAttempted = false;

        try (CustomerAwsSession session = retry.call(() -> gateway.open(
            account.roleArn(), account.externalId(), account.region(), AwsAccountService.sessionName(account)))) {
            List<TrackedInstance> tracked =
                instances.findAllByOwnerIdAndAccountId(account.ownerId(), account.id());
            if (needInstances || tracked.isEmpty()) {
                tracked = refreshInstances(session, account, tracked, job, now);
                instancesDone = true;
            }
            UsageOutcome outcome = UsageOutcome.NOT_ATTEMPTED;
            if (needUsage) {
                usageAttempted = true;
                outcome = refreshUsage(session, account, tracked, job, now);
                warnings.addAll(outcome.warnings());
                outcome.warnings().forEach(job::warn);
                if (outcome.complete()) {
                    job.usageDone();
                }
            }
            if (outcome.failure() != null) {
                AwsAccessException f = outcome.failure();
                job.partialFailure(f.code(), f.getMessage());
                account.collectionFailed(f.code(), f.getMessage(), f.breaksConnection(), clock.instant());
            } else {
                account.collectionSucceeded(clock.instant());
            }
            job.finish(clock.instant());
        } catch (AwsAccessException e) {
            job.fail(e.code(), e.getMessage(), clock.instant());
            account.collectionFailed(e.code(), e.getMessage(), e.breaksConnection(), clock.instant());
            if (e.breaksConnection() && needUsage) {
                // 고쳐야 하는 오류: 다음 확인은 1시간 뒤 (그 사이 조회마다 AssumeRole을 반복하지 않는다).
                usageAttempted = true;
            }
            if (account.instancesRefreshedAt() == null && !instancesDone) {
                // 보여 줄 이전 데이터가 없으면 원인을 그대로 알린다.
                markUsageAttempt(account, usageAttempted, now);
                saveAccountAndJob(account, job);
                throw e;
            }
            warnings.add("AWS 수집에 실패해 마지막으로 저장된 데이터를 보여 줍니다. " + e.getMessage());
        } catch (RuntimeException e) {
            // DB 오류 등. 이미 저장한 구간은 남아 있고, 진행 표시를 넘기지 않았으므로 다음 수집에서 다시 읽는다.
            job.fail("INTERNAL", "수집 중 내부 오류가 발생했습니다. 저장된 구간은 유지되고 다음 수집 때 이어서 가져옵니다.",
                clock.instant());
            syncJobs.save(job);
            throw e;
        }

        markUsageAttempt(account, usageAttempted, now);
        saveAccountAndJob(account, job);
        return freshness(account, instancesDone, usageAttempted && job.usageRefreshed(), warnings);
    }

    private List<TrackedInstance> refreshInstances(
        CustomerAwsSession session, AwsAccount account, List<TrackedInstance> tracked, SyncJob job, Instant now) {
        CustomerAwsSession.DescribeResult result = retry.call(session::describeInstances);
        List<TrackedInstance> merged = merge(account, tracked, result.instances(), now);
        unitOfWork.run(() -> {
            instances.saveAll(account.ownerId(), account.id(), merged);
            account.instancesRefreshed(now);
            accounts.save(account);
        });
        job.instancesDone(result.requestCount(), result.instances().size());
        return merged;
    }

    /**
     * 같은 instanceId는 갱신, 새 인스턴스는 추가, 이번 응답에 없는 인스턴스는 present=false (기록은 유지).
     */
    static List<TrackedInstance> merge(
        AwsAccount account, List<TrackedInstance> tracked, List<Ec2Instance> latest, Instant now) {
        Map<String, TrackedInstance> byId = new HashMap<>();
        tracked.forEach(t -> byId.put(t.instanceId(), t));
        List<TrackedInstance> merged = new ArrayList<>();
        for (Ec2Instance info : latest) {
            TrackedInstance existing = byId.remove(info.instanceId());
            merged.add(existing == null
                ? TrackedInstance.firstSeen(UUID.randomUUID(), account.ownerId(), account.id(), info, now)
                : existing.observed(info, now));
        }
        byId.values().forEach(gone -> merged.add(gone.present() ? gone.missing() : gone));
        return merged;
    }

    /**
     * @param warnings 응답·sync_job에 남길 안내
     * @param failure  CloudWatch 호출 실패 (없으면 null). 성공한 구간은 이미 저장되어 있다.
     * @param complete 수집할 인스턴스를 모두 끝냈는지
     */
    private record UsageOutcome(List<String> warnings, AwsAccessException failure, boolean complete) {
        static final UsageOutcome NOT_ATTEMPTED = new UsageOutcome(List.of(), null, true);
    }

    /**
     * 인스턴스마다 수집 구간을 30일 단위로 시간순으로 읽는다. 한 구간이 실패하면 그 인스턴스는 거기서 멈추고
     * usageCollectedUntil을 마지막 성공 구간의 끝으로만 옮긴다 (실패한 구간은 다음 수집에서 다시 읽는다).
     * <ul>
     *   <li>권한·Role·리전 오류, 재시도 후에도 요청 제한: 다른 인스턴스도 같은 결과일 것이므로 이번 수집을 멈춘다.
     *   <li>그 밖의 일시 오류: 그 인스턴스만 건너뛰고 나머지는 계속 수집한다.
     * </ul>
     */
    private UsageOutcome refreshUsage(
        CustomerAwsSession session, AwsAccount account, List<TrackedInstance> tracked, SyncJob job, Instant now) {
        int budget = settings.maxCwRequestsPerRefresh();
        int[] calls = {0};
        List<String> warnings = new ArrayList<>();
        AwsAccessException firstFailure = null;
        int failedInstances = 0;
        boolean complete = true;

        for (TrackedInstance instance : tracked) {
            if (!instance.needsUsageCollection()) {
                continue;
            }
            List<TimeWindow> windows = CollectionWindows.plan(
                instance.usageCollectedUntil(), now, settings.backfill(), settings.overlap());
            Instant collectedUntil = instance.usageCollectedUntil();
            boolean instanceDone = true;
            boolean budgetHit = false;
            AwsAccessException failure = null;
            for (TimeWindow window : windows) {
                if (calls[0] >= budget) {
                    instanceDone = false;
                    budgetHit = true;
                    break;
                }
                List<HourlySample> samples;
                try {
                    samples = retry.call(
                        () -> session.hourlySampleCounts(instance.instanceId(), window.start(), window.end()),
                        () -> {
                            calls[0]++;
                            job.addCloudWatchRequests(1, 0);
                        },
                        () -> calls[0] < budget);
                } catch (AwsAccessException e) {
                    instanceDone = false;
                    failure = e;
                    break;
                }
                int rows = usage.upsertHourly(account.ownerId(), account.id(), samples, now);
                job.addCloudWatchRequests(0, rows);
                collectedUntil = window.end();
            }

            // 저장한 구간까지만 진행 표시를 옮긴다 (인스턴스마다 바로 저장해 이후 실패와 무관하게 남긴다).
            if (instanceDone) {
                saveProgress(account, instance.usageCollected(collectedUntil));
            } else {
                complete = false;
                if (collectedUntil != null && !collectedUntil.equals(instance.usageCollectedUntil())) {
                    saveProgress(account, partial(instance, collectedUntil));
                }
            }
            if (budgetHit) {
                warnings.add("CloudWatch 호출 상한(" + budget + "건)에 도달해 일부 인스턴스는 다음 수집 때 이어서 가져옵니다.");
                break;
            }
            if (failure != null) {
                failedInstances++;
                if (firstFailure == null) {
                    firstFailure = failure;
                }
                if (failure.breaksConnection() || failure.category() == AwsAccessException.Category.THROTTLED) {
                    break;
                }
            }
        }
        if (firstFailure != null) {
            warnings.add("사용량 수집 실패: " + firstFailure.getMessage() + " (인스턴스 " + failedInstances
                + "개의 실패한 구간은 다음 수집 때 다시 가져옵니다. 이미 가져온 구간은 저장되었습니다.)");
        }
        return new UsageOutcome(List.copyOf(warnings), firstFailure, complete);
    }

    private static TrackedInstance partial(TrackedInstance instance, Instant collectedUntil) {
        return new TrackedInstance(
            instance.id(), instance.ownerId(), instance.accountId(), instance.info(), instance.present(),
            true, collectedUntil, instance.firstSeenAt(), instance.lastSeenAt());
    }

    private void saveProgress(AwsAccount account, TrackedInstance updated) {
        instances.saveAll(account.ownerId(), account.id(), List.of(updated));
    }

    private static void markUsageAttempt(AwsAccount account, boolean usageAttempted, Instant now) {
        if (usageAttempted) {
            // 성공·실패와 관계없이 CloudWatch를 부른 시각을 기록해 1시간에 1회만 호출되게 한다.
            account.usageRefreshed(now);
        }
    }

    private void saveAccountAndJob(AwsAccount account, SyncJob job) {
        unitOfWork.run(() -> {
            accounts.save(account);
            syncJobs.save(job);
        });
    }

    private Freshness freshness(AwsAccount account, boolean instancesFetched, boolean usageFetched,
                                List<String> warnings) {
        if (warnings.isEmpty() && account.status() == AwsAccount.Status.ERROR && account.lastErrorMessage() != null) {
            warnings = List.of("AWS 연결에 문제가 있어 마지막으로 저장된 데이터를 보여 줍니다. " + account.lastErrorMessage()
                + " (고치면 1시간 안에 자동으로 다시 확인해 복구합니다.)");
        }
        return new Freshness(
            account.instancesRefreshedAt(),
            !instancesFetched,
            account.usageRefreshedAt(),
            !usageFetched,
            policy.nextUsageRefreshAt(account.usageRefreshedAt()),
            List.copyOf(warnings));
    }
}
