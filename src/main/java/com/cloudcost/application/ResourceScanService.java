package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.application.port.CustomerAwsSession.RegionScan;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceScan;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 확장 수집 (ADR-020): 켜진 모든 리전에서 볼륨·고정 IP·스냅샷·로드밸런서와, 기본 리전이 아닌 리전의 서버를 읽어 저장한다.
 * <ul>
 *   <li>6시간에 한 번만 다시 훑는다. 권한이 없던 연결은 스택을 업데이트했는지 1시간마다 다시 확인한다.
 *   <li>이전 템플릿(v1)으로 만든 Role은 새 액션이 AccessDenied → 연결 오류가 아니라 PERMISSION_UPGRADE_REQUIRED로 남긴다
 *       (EC2 사용량 수집은 그대로 동작한다).
 *   <li>리전은 동시에 최대 {@link #PARALLEL_REGIONS}곳씩 읽는다.
 *   <li>화면 조회({@link #requestRefresh})는 수집을 기다리지 않는다. 오래됐으면 백그라운드에서 다시 훑고 저장된 상태를 바로 돌려준다
 *       (느린 리전 하나가 기존 /waste 응답을 막지 않게). 예약 수집은 {@link #refreshIfStale}로 끝까지 기다린다.
 *   <li>시도한 Describe·GetMetricStatistics 수(실패 포함)를 수집 상태에 남긴다 (고객 계정 CloudWatch 무료 한도 추적).
 * </ul>
 */
public class ResourceScanService {

    static final int PARALLEL_REGIONS = 6;

    private final AwsAccountRepository accounts;
    private final AccountResourceRepository resources;
    private final CustomerAwsGateway gateway;
    private final Clock clock;
    private final Executor background;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    /** @param background 화면 조회가 맡기는 백그라운드 수집용 (테스트는 바로 실행하는 Executor를 넣는다) */
    public ResourceScanService(
        AwsAccountRepository accounts, AccountResourceRepository resources, CustomerAwsGateway gateway, Clock clock,
        Executor background) {
        this.accounts = accounts;
        this.resources = resources;
        this.gateway = gateway;
        this.clock = clock;
        this.background = background;
    }

    /** 지금 상태 (수집하지 않는다) */
    public ResourceScan current(String ownerId, UUID accountId) {
        return resources.findScanByOwnerIdAndAccountId(ownerId, accountId).orElse(ResourceScan.notScanned());
    }

    /**
     * 화면 조회용: 오래됐으면 백그라운드 수집을 맡기고(같은 연결은 한 번만), 기다리지 않는다.
     *
     * @return 수집이 진행 중이거나 방금 맡겼으면 true
     */
    public boolean requestRefresh(String ownerId, UUID accountId) {
        if (!current(ownerId, accountId).stale(clock.instant())) {
            return pending.contains(accountId);
        }
        if (!pending.add(accountId)) {
            return true;
        }
        try {
            background.execute(() -> {
                try {
                    refreshIfStale(ownerId, accountId);
                } finally {
                    pending.remove(accountId);
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(accountId);
            return false;
        }
        return pending.contains(accountId);
    }

    /** 오래됐으면 다시 훑는다 (끝날 때까지 기다린다). 수집할 수 없는 연결이면 지금 상태를 그대로 돌려준다. AWS 오류는 상태로 남기고 던지지 않는다. */
    public ResourceScan refreshIfStale(String ownerId, UUID accountId) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, id -> new ReentrantLock());
        lock.lock();
        try {
            AwsAccount account = accounts.findByOwnerIdAndId(ownerId, accountId).orElse(null);
            ResourceScan scan = current(ownerId, accountId);
            Instant now = clock.instant();
            if (account == null || !account.canCollect() || !scan.stale(now)) {
                return scan;
            }
            return scan(account, scan, now);
        } finally {
            lock.unlock();
        }
    }

    private ResourceScan scan(AwsAccount account, ResourceScan previous, Instant now) {
        String home = account.region().value();
        CustomerAwsSession.ScanCalls[] calls = {new CustomerAwsSession.ScanCalls(0, 0)};
        try (CustomerAwsSession session = gateway.open(
            account.roleArn(), account.externalId(), account.region(), AwsAccountService.sessionName(account))) {
            try {
                return scanRegions(account, session, home, now);
            } finally {
                calls[0] = session.scanCalls();
            }
        } catch (ExecutionException e) {
            return failed(account, previous, now, e.getCause(), calls[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(account, previous, now, e, calls[0]);
        } catch (AwsAccessException e) {
            return failed(account, previous, now, e, calls[0]);
        }
    }

    private ResourceScan scanRegions(AwsAccount account, CustomerAwsSession session, String home, Instant now)
        throws ExecutionException, InterruptedException {
        List<String> regions = session.enabledRegions();
        List<AwsResource> found = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_REGIONS, Math.max(1, regions.size())));
        try {
            List<Future<RegionScan>> futures = new ArrayList<>();
            for (String region : regions) {
                futures.add(pool.submit(() -> session.scanRegion(region, !region.equals(home), now)));
            }
            for (Future<RegionScan> f : futures) {
                found.addAll(f.get().resources());
            }
        } finally {
            pool.shutdownNow();
            // 실패로 끝낼 때도 아직 도는 리전의 호출 수까지 세려고 잠깐 기다린다
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        }
        CustomerAwsSession.ScanCalls done = session.scanCalls();
        ResourceScan ok = new ResourceScan(ResourceScan.Status.OK, ResourceScan.CURRENT_PERMISSION_VERSION, now,
            null, done.describeCount(), done.cloudWatchCount());
        resources.replaceAll(account.ownerId(), account.id(), found, ok, now);
        return ok;
    }

    /**
     * 권한 거부만으로는 이전 템플릿(v1)인지 알 수 없다 (v2 Role도 SCP·권한 경계·명시적 Deny에 막힐 수 있다).
     * 그래서 permissionVersion은 "확인되지 않음"(1)으로 두고, 안내문에 두 가능성을 모두 적는다.
     */
    private ResourceScan failed(
        AwsAccount account, ResourceScan previous, Instant now, Throwable cause, CustomerAwsSession.ScanCalls calls) {
        ResourceScan scan;
        if (cause instanceof AwsAccessException e && e.category() == AwsAccessException.Category.PERMISSION_DENIED) {
            String reason = previous.permissionVersion() >= ResourceScan.CURRENT_PERMISSION_VERSION
                ? "전에는 확인됐던 권한이에요. 조직 정책(SCP)·권한 경계·거부 정책이 새로 생겼는지 확인해 주세요."
                : "이전 템플릿으로 연결했다면 스택을 업데이트해 주세요. 이미 최신이면 조직 정책(SCP)·권한 경계·거부 정책을 확인해 주세요.";
            scan = new ResourceScan(ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED, 1, now,
                "방치 리소스를 찾을 권한이 없어요 (" + e.action() + " 거부). " + reason,
                calls.describeCount(), calls.cloudWatchCount());
        } else if (cause instanceof AwsAccessException e) {
            scan = new ResourceScan(ResourceScan.Status.FAILED, previous.permissionVersion(), now, e.getMessage(),
                calls.describeCount(), calls.cloudWatchCount());
        } else if (cause instanceof RuntimeException e) {
            throw e;
        } else {
            scan = new ResourceScan(ResourceScan.Status.FAILED, previous.permissionVersion(), now,
                "방치 리소스 조회가 중단됐어요. 잠시 뒤 다시 확인할게요.", calls.describeCount(), calls.cloudWatchCount());
        }
        resources.saveScan(account.ownerId(), account.id(), scan);
        return scan;
    }
}
