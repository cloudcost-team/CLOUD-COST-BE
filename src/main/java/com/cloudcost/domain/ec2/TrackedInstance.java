package com.cloudcost.domain.ec2;

import java.time.Instant;
import java.util.UUID;

/**
 * DB에 보관하는 인스턴스 = 마지막으로 본 정보 + 수집 상태.
 *
 * @param present              마지막 DescribeInstances 결과에 있었는지. 삭제되어 사라져도 사용 기록은 남긴다.
 * @param activeSinceUsageSync 마지막 CloudWatch 수집 이후 켜져 있는 모습을 한 번이라도 봤는지. 계속 꺼져 있던 인스턴스는 CloudWatch를 다시 부르지 않기 위해 쓴다.
 * @param usageCollectedUntil  이 시각 전까지 CloudWatch 사용량을 수집했다. null이면 아직 소급 수집 전.
 */
public record TrackedInstance(
    UUID id,
    String ownerId,
    UUID accountId,
    Ec2Instance info,
    boolean present,
    boolean activeSinceUsageSync,
    Instant usageCollectedUntil,
    Instant firstSeenAt,
    Instant lastSeenAt) {

    public static TrackedInstance firstSeen(UUID id, String ownerId, UUID accountId, Ec2Instance info, Instant now) {
        return new TrackedInstance(
            id, ownerId, accountId, info, true, InstanceStates.mayEmitMetrics(info.state()), null, now, now);
    }

    public TrackedInstance observed(Ec2Instance latest, Instant now) {
        boolean active = activeSinceUsageSync || InstanceStates.mayEmitMetrics(latest.state());
        return new TrackedInstance(
            id, ownerId, accountId, latest, true, active, usageCollectedUntil, firstSeenAt, now);
    }

    public TrackedInstance missing() {
        return new TrackedInstance(
            id, ownerId, accountId, info, false, activeSinceUsageSync, usageCollectedUntil, firstSeenAt, lastSeenAt);
    }

    public TrackedInstance usageCollected(Instant until) {
        return new TrackedInstance(id, ownerId, accountId, info, present, false, until, firstSeenAt, lastSeenAt);
    }

    /**
     * 처음 보는 인스턴스(소급 수집 필요)이거나, 지난 수집 이후 켜져 있었을 수 있으면 CloudWatch를 조회한다.
     */
    public boolean needsUsageCollection() {
        return usageCollectedUntil == null
            || activeSinceUsageSync
            || (present && InstanceStates.mayEmitMetrics(info.state()));
    }

    public String instanceId() {
        return info.instanceId();
    }
}
