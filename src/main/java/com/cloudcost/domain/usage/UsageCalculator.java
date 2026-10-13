package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 시간별 CloudWatch 데이터 포인트 → 사용 분(minute).
 *
 * <p>EC2 CPUUtilization은 켜져 있는 동안 1분마다 샘플을 남긴다 (기본 모니터링은 5분 데이터 포인트 × 샘플 5개,
 * 세부 모니터링은 1분 데이터 포인트 × 샘플 1개). 그래서 1시간 버킷의 SampleCount가 곧 그 시간에 켜져 있던 분이다.
 * 실제 계정에서 확인: 꽉 찬 1시간 = 60, 13분 실행 = 13 (2026-10-13 QA).
 *
 * <p>데이터 포인트가 없으면 꺼져 있던 시간이다. 오차로 60을 넘는 경우는 60분으로 자른다.
 */
public final class UsageCalculator {

    public static final int MINUTES_PER_HOUR = 60;

    private UsageCalculator() {}

    public static long usedMinutes(BigDecimal sampleCount) {
        if (sampleCount == null || sampleCount.signum() <= 0) {
            return 0;
        }
        long minutes = sampleCount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Math.min(MINUTES_PER_HOUR, Math.max(1, minutes));
    }
}
