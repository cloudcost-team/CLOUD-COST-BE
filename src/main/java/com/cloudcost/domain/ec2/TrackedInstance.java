package com.cloudcost.domain.ec2;

import java.time.Instant;
import java.util.UUID;

/**
 * DB에 보관하는 인스턴스 = 마지막으로 본 정보 + 수집 상태.
 *
 * @param present              마지막 DescribeInstances 결과에 있었는지. 삭제되어 사라져도 사용 기록은 남긴다.
 * @param activeSinceUsageSync 마지막 CloudWatch 수집 이후 켜져 있는 모습을 한 번이라도 봤는지. 계속 꺼져 있던 인스턴스는 CloudWatch를 다시 부르지 않기 위해 쓴다.
 * @param usageCollectedUntil  이 시각 전까지 CloudWatch 사용량을 수집했다. null이면 아직 소급 수집 전.
 * @param metricsCollectedUntil 이 시각 전까지 낭비 탐지용 지표(CPU 평균·최대, 네트워크)를 최근 14일 범위에서 수집했다.
 *                              null이면 아직 수집 전 (지표 수집 기능 이전에 저장한 인스턴스 포함).
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
    Instant lastSeenAt,
    Instant metricsCollectedUntil) {

    public static TrackedInstance firstSeen(UUID id, String ownerId, UUID accountId, Ec2Instance info, Instant now) {
        return new TrackedInstance(
            id, ownerId, accountId, info, true, InstanceStates.mayEmitMetrics(info.state()), null, now, now, null);
    }

    public TrackedInstance observed(Ec2Instance latest, Instant now) {
        boolean active = activeSinceUsageSync || InstanceStates.mayEmitMetrics(latest.state());
        return new TrackedInstance(
            id, ownerId, accountId, latest, true, active, usageCollectedUntil, firstSeenAt, now,
            metricsCollectedUntil);
    }

    public TrackedInstance missing() {
        return new TrackedInstance(
            id, ownerId, accountId, info, false, activeSinceUsageSync, usageCollectedUntil, firstSeenAt, lastSeenAt,
            metricsCollectedUntil);
    }

    public TrackedInstance usageCollected(Instant until) {
        return new TrackedInstance(
            id, ownerId, accountId, info, present, false, until, firstSeenAt, lastSeenAt, metricsCollectedUntil);
    }

    public TrackedInstance metricsCollected(Instant until) {
        return new TrackedInstance(
            id, ownerId, accountId, info, present, activeSinceUsageSync, usageCollectedUntil, firstSeenAt, lastSeenAt,
            until);
    }

    /**
     * 처음 보는 인스턴스(소급 수집 필요)이거나, 지난 수집 이후 켜져 있었을 수 있으면 CloudWatch를 조회한다.
     */
    public boolean needsUsageCollection() {
        return usageCollectedUntil == null
            || activeSinceUsageSync
            || (present && InstanceStates.mayEmitMetrics(info.state()));
    }

    /**
     * 낭비 탐지용 지표(최근 14일)가 사용 시간 수집보다 뒤처져 있으면 다시 수집한다.
     * 사용 시간 수집과 따로 판단해야, 지금 꺼져 있는 인스턴스도 지표 수집 전 기록이나 실패한 네트워크 구간을 채운다 (ADR-009).
     * 소급 전(usageCollectedUntil 없음)이면 사용 시간 수집이 먼저다.
     */
    public boolean needsMetricsCollection() {
        return usageCollectedUntil != null
            && (metricsCollectedUntil == null || metricsCollectedUntil.isBefore(usageCollectedUntil));
    }

    public String instanceId() {
        return info.instanceId();
    }
}
