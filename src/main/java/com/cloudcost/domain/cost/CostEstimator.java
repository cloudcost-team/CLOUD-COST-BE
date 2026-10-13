package com.cloudcost.domain.cost;

import com.cloudcost.domain.usage.UsageCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용 시간 × 온디맨드 시간당 단가 = 추정 비용. 단가가 없으면 금액은 null이다.
 */
public final class CostEstimator {

    private static final int SCALE = 6;

    private CostEstimator() {
    }

    public static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes)
            .divide(BigDecimal.valueOf(UsageCalculator.MINUTES_PER_HOUR), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * @return 단가가 null이면 null (0이 아니다)
     */
    public static BigDecimal estimate(long minutes, BigDecimal hourlyUsd) {
        if (hourlyUsd == null) {
            return null;
        }
        return hours(minutes).multiply(hourlyUsd).setScale(SCALE, RoundingMode.HALF_UP);
    }

    public record InstanceCost(String instanceId, long usedMinutes, BigDecimal hourlyUsd, BigDecimal estimatedCost) {
    }

    /**
     * @param estimatedCost 단가가 있는 인스턴스 금액의 합. 사용량이 있는데 단가가 하나도 없으면 null.
     */
    public record PeriodCost<K>(K period, long usedMinutes, BigDecimal estimatedCost, List<InstanceCost> instances) {
    }

    /**
     * @param unpricedMinutes 단가가 없어 금액에 포함되지 않은 사용 분
     */
    public record CostReport<K>(
        List<PeriodCost<K>> periods,
        long totalMinutes,
        BigDecimal totalEstimatedCost,
        long unpricedMinutes,
        List<String> unpricedInstanceIds) {
    }

    public static <K> CostReport<K> report(Map<K, Map<String, Long>> minutesByPeriod,
                                           Map<String, InstancePrice> prices) {
        List<PeriodCost<K>> periods = new ArrayList<>();
        Set<String> unpriced = new LinkedHashSet<>();
        long totalMinutes = 0;
        long unpricedMinutes = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        boolean anyPriced = false;

        for (Map.Entry<K, Map<String, Long>> period : minutesByPeriod.entrySet()) {
            List<InstanceCost> rows = new ArrayList<>();
            long periodMinutes = 0;
            BigDecimal periodCost = BigDecimal.ZERO;
            boolean periodPriced = false;
            for (Map.Entry<String, Long> usage : period.getValue().entrySet()) {
                long minutes = usage.getValue();
                if (minutes <= 0) {
                    continue;
                }
                InstancePrice price = prices.get(usage.getKey());
                BigDecimal hourly = price == null ? null : price.hourlyUsd();
                BigDecimal cost = estimate(minutes, hourly);
                rows.add(new InstanceCost(usage.getKey(), minutes, hourly, cost));
                periodMinutes += minutes;
                if (cost == null) {
                    unpriced.add(usage.getKey());
                    unpricedMinutes += minutes;
                } else {
                    periodCost = periodCost.add(cost);
                    periodPriced = true;
                }
            }
            totalMinutes += periodMinutes;
            if (periodPriced) {
                totalCost = totalCost.add(periodCost);
                anyPriced = true;
            }
            BigDecimal shown = periodMinutes == 0 ? BigDecimal.ZERO : (periodPriced ? periodCost : null);
            periods.add(new PeriodCost<>(period.getKey(), periodMinutes, shown, rows));
        }
        BigDecimal total = totalMinutes == 0 ? BigDecimal.ZERO : (anyPriced ? totalCost : null);
        return new CostReport<>(periods, totalMinutes, total, unpricedMinutes, List.copyOf(unpriced));
    }
}
