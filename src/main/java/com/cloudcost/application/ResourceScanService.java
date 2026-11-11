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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 확장 수집 (ADR-020): 켜진 모든 리전에서 볼륨·고정 IP·스냅샷·로드밸런서와, 기본 리전이 아닌 리전의 서버를 읽어 저장한다.
 * <ul>
 *   <li>6시간에 한 번만 다시 훑는다. 권한이 없던 연결은 스택을 업데이트했는지 1시간마다 다시 확인한다.
 *   <li>이전 템플릿(v1)으로 만든 Role은 새 액션이 AccessDenied → 연결 오류가 아니라 PERMISSION_UPGRADE_REQUIRED로 남긴다
 *       (EC2 사용량 수집은 그대로 동작한다).
 *   <li>리전은 동시에 최대 {@link #PARALLEL_REGIONS}곳씩 읽는다.
 * </ul>
 */
public class ResourceScanService {

    static final int PARALLEL_REGIONS = 6;

    private final AwsAccountRepository accounts;
    private final AccountResourceRepository resources;
    private final CustomerAwsGateway gateway;
    private final Clock clock;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ResourceScanService(
        AwsAccountRepository accounts, AccountResourceRepository resources, CustomerAwsGateway gateway, Clock clock) {
        this.accounts = accounts;
        this.resources = resources;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** 지금 상태 (수집하지 않는다) */
    public ResourceScan current(String ownerId, UUID accountId) {
        return resources.findScanByOwnerIdAndAccountId(ownerId, accountId).orElse(ResourceScan.notScanned());
    }

    /** 오래됐으면 다시 훑는다. 수집할 수 없는 연결이면 지금 상태를 그대로 돌려준다. AWS 오류는 상태로 남기고 던지지 않는다. */
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
        try (CustomerAwsSession session = gateway.open(
            account.roleArn(), account.externalId(), account.region(), AwsAccountService.sessionName(account))) {
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
            }
            ResourceScan ok = new ResourceScan(
                ResourceScan.Status.OK, ResourceScan.CURRENT_PERMISSION_VERSION, now, null);
            resources.replaceAll(account.ownerId(), account.id(), found, ok, now);
            return ok;
        } catch (ExecutionException e) {
            return failed(account, previous, now, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(account, previous, now, e);
        } catch (AwsAccessException e) {
            return failed(account, previous, now, e);
        }
    }

    private ResourceScan failed(AwsAccount account, ResourceScan previous, Instant now, Throwable cause) {
        ResourceScan scan;
        if (cause instanceof AwsAccessException e && e.category() == AwsAccessException.Category.PERMISSION_DENIED) {
            scan = new ResourceScan(ResourceScan.Status.PERMISSION_UPGRADE_REQUIRED, 1, now,
                "방치 리소스를 찾으려면 권한 업데이트가 필요해요 (" + e.action() + " 권한 없음).");
        } else if (cause instanceof AwsAccessException e) {
            scan = new ResourceScan(ResourceScan.Status.FAILED, previous.permissionVersion(), now, e.getMessage());
        } else if (cause instanceof RuntimeException e) {
            throw e;
        } else {
            scan = new ResourceScan(ResourceScan.Status.FAILED, previous.permissionVersion(), now,
                "방치 리소스 조회가 중단됐어요. 잠시 뒤 다시 확인할게요.");
        }
        resources.saveScan(account.ownerId(), account.id(), scan);
        return scan;
    }
}
