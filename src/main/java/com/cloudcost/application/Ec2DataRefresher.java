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
 * </ul>
 * 같은 계정을 동시에 조회해도 AWS는 한 번만 부르도록 계정 단위로 잠근다.
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
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Ec2DataRefresher(
        AwsAccountRepository accounts,
        Ec2InstanceRepository instances,
        Ec2UsageRepository usage,
        SyncJobRepository syncJobs,
        CustomerAwsGateway gateway,
        UnitOfWork unitOfWork,
        Clock clock,
        UsageSettings settings) {
        this.accounts = accounts;
        this.instances = instances;
        this.usage = usage;
        this.syncJobs = syncJobs;
        this.gateway = gateway;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.settings = settings;
        this.policy = settings.refreshPolicy();
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
        boolean needInstances = policy.instancesStale(account.instancesRefreshedAt(), now);
        boolean needUsage = policy.usageStale(account.usageRefreshedAt(), now);
        if (!needInstances && !needUsage) {
            return freshness(account, false, false, List.of());
        }

        SyncJob job = syncJobs.save(SyncJob.start(
            UUID.randomUUID(), account.ownerId(), account.id(), SyncJob.Trigger.QUERY, now));
        List<String> warnings = new ArrayList<>();
        boolean instancesDone = false;
        boolean usageAttempted = false;

        try (CustomerAwsSession session = gateway.open(
            account.roleArn(), account.externalId(), account.region(), AwsAccountService.sessionName(account))) {
            List<TrackedInstance> tracked =
                instances.findAllByOwnerIdAndAccountId(account.ownerId(), account.id());
            if (needInstances || tracked.isEmpty()) {
                tracked = refreshInstances(session, account, tracked, job, now);
                instancesDone = true;
            }
            if (needUsage) {
                usageAttempted = true;
                String warning = refreshUsage(session, account, tracked, job, now);
                if (warning != null) {
                    warnings.add(warning);
                    job.warn(warning);
                } else {
                    job.usageDone();
                }
            }
            account.clearError(now);
            job.finish(clock.instant());
        } catch (AwsAccessException e) {
            job.fail(e.code(), e.getMessage(), clock.instant());
            account.recordError(e.code(), e.getMessage(), clock.instant());
            if (account.instancesRefreshedAt() == null && !instancesDone) {
                // 보여 줄 이전 데이터가 없으면 원인을 그대로 알린다.
                saveAccountAndJob(account, job);
                throw e;
            }
            warnings.add("AWS 수집에 실패해 마지막으로 저장된 데이터를 보여 줍니다. " + e.getMessage());
        }

        if (usageAttempted) {
            // 성공·실패와 관계없이 CloudWatch를 부른 시각을 기록해 1시간에 1회만 호출되게 한다.
            account.usageRefreshed(now);
        }
        saveAccountAndJob(account, job);
        return freshness(account, instancesDone, usageAttempted && job.usageRefreshed(), warnings);
    }

    private List<TrackedInstance> refreshInstances(
        CustomerAwsSession session, AwsAccount account, List<TrackedInstance> tracked, SyncJob job, Instant now) {
        CustomerAwsSession.DescribeResult result = session.describeInstances();
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
     * @return 경고 메시지 (전부 성공하면 null)
     */
    private String refreshUsage(
        CustomerAwsSession session, AwsAccount account, List<TrackedInstance> tracked, SyncJob job, Instant now) {
        int budget = settings.maxCwRequestsPerRefresh();
        int calls = 0;
        String warning = null;
        List<TrackedInstance> updated = new ArrayList<>();

        for (TrackedInstance instance : tracked) {
            if (!instance.needsUsageCollection()) {
                continue;
            }
            List<TimeWindow> windows = CollectionWindows.plan(
                instance.usageCollectedUntil(), now, settings.backfill(), settings.overlap());
            Instant collectedUntil = instance.usageCollectedUntil();
            boolean complete = true;
            for (TimeWindow window : windows) {
                if (calls >= budget) {
                    complete = false;
                    warning = "CloudWatch 호출 상한(" + budget + "건)에 도달해 일부 인스턴스는 다음 수집 때 이어서 가져옵니다.";
                    break;
                }
                List<HourlySample> samples;
                try {
                    samples = session.hourlySampleCounts(instance.instanceId(), window.start(), window.end());
                    calls++;
                } catch (AwsAccessException e) {
                    calls++;
                    job.addCloudWatchRequests(1, 0);
                    persistUsageProgress(account, updated);
                    return "사용량 수집 실패: " + e.getMessage();
                }
                int rows = usage.upsertHourly(account.ownerId(), account.id(), samples, now);
                job.addCloudWatchRequests(1, rows);
                collectedUntil = window.end();
            }
            if (complete) {
                updated.add(instance.usageCollected(collectedUntil));
            } else {
                if (collectedUntil != null && !collectedUntil.equals(instance.usageCollectedUntil())) {
                    updated.add(partial(instance, collectedUntil));
                }
                break;
            }
        }
        persistUsageProgress(account, updated);
        return warning;
    }

    private static TrackedInstance partial(TrackedInstance instance, Instant collectedUntil) {
        return new TrackedInstance(
            instance.id(), instance.ownerId(), instance.accountId(), instance.info(), instance.present(),
            true, collectedUntil, instance.firstSeenAt(), instance.lastSeenAt());
    }

    private void persistUsageProgress(AwsAccount account, List<TrackedInstance> updated) {
        if (!updated.isEmpty()) {
            instances.saveAll(account.ownerId(), account.id(), updated);
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
        return new Freshness(
            account.instancesRefreshedAt(),
            !instancesFetched,
            account.usageRefreshedAt(),
            !usageFetched,
            policy.nextUsageRefreshAt(account.usageRefreshedAt()),
            List.copyOf(warnings));
    }
}
