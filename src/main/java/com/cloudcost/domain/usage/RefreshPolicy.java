package com.cloudcost.domain.usage;

import java.time.Duration;
import java.time.Instant;

/**
 * 조회 시점에 AWS를 다시 부를지 정한다. 기준 시간 안이면 DB에 저장된 값으로 응답한다.
 *
 * @param instanceTtl DescribeInstances 재조회 간격 (무료 API, 기본 10분)
 * @param usageTtl CloudWatch 재조회 간격 (고객 계정 무료 한도를 쓰므로 기본 1시간)
 */
public record RefreshPolicy(Duration instanceTtl, Duration usageTtl) {

    public boolean instancesStale(Instant lastRefreshedAt, Instant now) {
        return isStale(lastRefreshedAt, now, instanceTtl);
    }

    public boolean usageStale(Instant lastRefreshedAt, Instant now) {
        return isStale(lastRefreshedAt, now, usageTtl);
    }

    public Instant nextUsageRefreshAt(Instant lastRefreshedAt) {
        return lastRefreshedAt == null ? null : lastRefreshedAt.plus(usageTtl);
    }

    private static boolean isStale(Instant last, Instant now, Duration ttl) {
        return last == null || !now.isBefore(last.plus(ttl));
    }
}
