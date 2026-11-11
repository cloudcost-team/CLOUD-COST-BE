package com.cloudcost.domain.resource;

import java.time.Duration;
import java.time.Instant;

/**
 * 연결 1개의 확장 수집(볼륨·고정 IP·스냅샷·LB·다른 리전 서버) 상태 (ADR-020).
 *
 * @param permissionVersion 1 = EC2·CloudWatch 두 권한(이전 템플릿), 2 = 확장 권한까지 확인됨
 * @param message           실패·권한 부족 사유 (한국어). 성공이면 null
 */
public record ResourceScan(Status status, int permissionVersion, Instant scannedAt, String message) {

    public enum Status {
        NOT_SCANNED,
        OK,
        /** 이전 템플릿으로 만든 Role이라 확장 권한이 없다 → 스택 업데이트 안내 */
        PERMISSION_UPGRADE_REQUIRED,
        FAILED
    }

    public static final int CURRENT_PERMISSION_VERSION = 2;
    /** 리소스는 자주 바뀌지 않으므로 6시간에 한 번만 다시 훑는다 (Describe는 무료지만 리전 수만큼 호출한다) */
    public static final Duration TTL = Duration.ofHours(6);
    /** 권한이 없던 연결은 사용자가 스택을 업데이트했는지 1시간마다 다시 확인한다 */
    public static final Duration RETRY_AFTER_UPGRADE_REQUIRED = Duration.ofHours(1);

    public static ResourceScan notScanned() {
        return new ResourceScan(Status.NOT_SCANNED, 1, null, null);
    }

    public boolean stale(Instant now) {
        if (scannedAt == null) {
            return true;
        }
        Duration ttl = status == Status.OK ? TTL : RETRY_AFTER_UPGRADE_REQUIRED;
        return !scannedAt.plus(ttl).isAfter(now);
    }
}
