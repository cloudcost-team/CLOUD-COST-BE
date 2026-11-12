package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.sync.SyncJob;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 예약 수집 (ADR-019). 스케줄러가 수집할 연결을 큐에 넣고, 워커가 하나씩 꺼내 기존 수집기로 수집한 뒤 알림 조건을 평가한다.
 * 수집 규칙(인스턴스 10분·CloudWatch 1시간 캐시, 1회 호출 상한)은 조회 시점 수집과 같아서 고객 계정 호출이 늘지 않는다.
 */
public class ScheduledCollectionService {

    /** 큐에 넣을 작업 1건 */
    public record Target(String ownerId, UUID accountId) {}

    public enum Outcome {
        /** 수집·평가를 마쳤다 (AWS 오류는 연결 상태와 sync_job에 남겼다) */
        DONE,
        /** 연결이 지워졌거나 수집할 수 없는 상태라 건너뛰었다 */
        SKIPPED
    }

    private final AwsAccountRepository accounts;
    private final Ec2DataRefresher refresher;
    private final ResourceScanService resourceScans;
    private final AlertEvaluationService alerts;

    public ScheduledCollectionService(
        AwsAccountRepository accounts, Ec2DataRefresher refresher, ResourceScanService resourceScans,
        AlertEvaluationService alerts) {
        this.accounts = accounts;
        this.refresher = refresher;
        this.resourceScans = resourceScans;
        this.alerts = alerts;
    }

    public List<Target> targets() {
        return accounts.findCollectableForSchedule().stream()
            .map(a -> new Target(a.ownerId(), a.id()))
            .toList();
    }

    /**
     * owner와 연결 id가 함께 맞는 연결만 수집한다 (메시지 값만으로 남의 연결을 건드리지 않는다).
     * AWS 오류는 수집기가 연결 상태에 기록하므로 여기서 끝낸다. 그 밖의 오류(DB 등)는 던져서 큐가 다시 시도하게 한다.
     */
    public Outcome collect(String ownerId, UUID accountId) {
        Optional<AwsAccount> found = accounts.findByOwnerIdAndId(ownerId, accountId);
        if (found.isEmpty() || !found.get().canCollect()) {
            return Outcome.SKIPPED;
        }
        try {
            refresher.refreshIfStale(ownerId, accountId, SyncJob.Trigger.SCHEDULED);
        } catch (AwsAccessException e) {
            return Outcome.DONE;
        }
        // 방치 리소스도 미리 훑어 둔다 (6시간 캐시, 권한이 없으면 상태만 남긴다 — ADR-020).
        // 여기서 예상 밖 오류가 나도 알림 평가는 먼저 하고, 그다음 던져 큐가 다시 시도하게 한다.
        RuntimeException resourceFailure = null;
        try {
            resourceScans.refreshIfStale(ownerId, accountId);
        } catch (RuntimeException e) {
            resourceFailure = e;
        }
        // 수집 뒤 상태(ERROR 복구·전환)를 반영해 다시 읽는다
        accounts.findByOwnerIdAndId(ownerId, accountId).ifPresent(a -> alerts.evaluate(ownerId, a));
        if (resourceFailure != null) {
            throw resourceFailure;
        }
        return Outcome.DONE;
    }
}
