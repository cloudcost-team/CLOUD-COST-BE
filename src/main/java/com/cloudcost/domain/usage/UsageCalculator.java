package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 시간별 CloudWatch 데이터 포인트 → 사용 분(minute).
 *
 * <p>기본 규칙(기획서): 데이터 포인트가 있는 시간 = 켜져 있던 시간.
 * 보정: 한 시간 안의 샘플 수(SampleCount)를 그 인스턴스의 "꽉 찬 한 시간" 샘플 수와 비교해 분 단위로 근사한다.
 * 예) 꽉 찬 한 시간이 12샘플인데 2샘플이면 10분.
 *
 * <p>"꽉 찬 한 시간" 샘플 수는 그 인스턴스에서 관측된 최대 SampleCount로 정한다.
 * 한 번도 한 시간을 꽉 채워 실행한 적이 없으면 보정이 과대해지지만, 최대 60분으로 막아
 * 기본 규칙(데이터 포인트가 있으면 1시간)보다 나빠지지 않는다.
 */
public final class UsageCalculator {

    public static final int MINUTES_PER_HOUR = 60;

    private UsageCalculator() {}

    public static long usedMinutes(BigDecimal sampleCount, BigDecimal fullHourSamples) {
        if (sampleCount == null || sampleCount.signum() <= 0) {
            return 0;
        }
        if (fullHourSamples == null || fullHourSamples.compareTo(sampleCount) < 0) {
            fullHourSamples = sampleCount;
        }
        long minutes = sampleCount
                .multiply(BigDecimal.valueOf(MINUTES_PER_HOUR))
                .divide(fullHourSamples, 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Math.min(MINUTES_PER_HOUR, Math.max(1, minutes));
    }

    /** 인스턴스별 최대 SampleCount. 조회 범위가 아니라 저장된 전체 기록에서 구해야 정확하다. */
    public static Map<String, BigDecimal> fullHourSamples(Collection<HourlySample> samples) {
        Map<String, BigDecimal> max = new HashMap<>();
        for (HourlySample s : samples) {
            max.merge(s.instanceId(), s.sampleCount(), BigDecimal::max);
        }
        return max;
    }
}
