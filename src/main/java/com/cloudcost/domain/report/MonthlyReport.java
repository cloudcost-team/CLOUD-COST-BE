package com.cloudcost.domain.report;

import com.cloudcost.domain.cost.CostEstimator.InstanceCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 월간 리포트 계산 (이슈 #37, S10). 저장하지 않고 조회할 때 계산한다. 금액은 USD다.
 */
public final class MonthlyReport {

    public static final int TOP_SERVERS = 3;

    private MonthlyReport() {
    }

    public enum Status {
        /** 끝난 달 */
        FINAL,
        /** 진행 중인 이번 달 (값이 바뀐다) */
        PREVIEW,
        /** 기록이 시작된 첫 달 (전월이 없어 비교하지 않음, 첫날부터의 기록이 아닐 수 있다) */
        FIRST_MONTH,
        /** 그 달에 사용 기록이 없음 */
        EMPTY
    }

    /**
     * 우선순위: EMPTY → FIRST_MONTH → PREVIEW → FINAL.
     *
     * @param firstMonth 기록이 시작된 달 (null이면 기록 없음)
     */
    public static Status status(YearMonth month, YearMonth current, YearMonth firstMonth, long usedMinutes) {
        if (firstMonth == null || month.isBefore(firstMonth) || usedMinutes == 0) {
            return Status.EMPTY;
        }
        if (month.equals(firstMonth)) {
            return Status.FIRST_MONTH;
        }
        return month.equals(current) ? Status.PREVIEW : Status.FINAL;
    }

    /** 전월 대비 증감률 (%). 비교할 수 없거나 전월이 0이면 null */
    public static BigDecimal changeRate(BigDecimal currentUsd, BigDecimal previousUsd) {
        if (currentUsd == null || previousUsd == null || previousUsd.signum() == 0) {
            return null;
        }
        return currentUsd.subtract(previousUsd).multiply(BigDecimal.valueOf(100))
            .divide(previousUsd, 1, RoundingMode.HALF_UP);
    }

    /**
     * 서버별 비중 한 줄.
     *
     * @param instanceId 기타면 null
     * @param sharePercent 그 달 추정 비용 중 비중 (%)
     */
    public record Share(String instanceId, BigDecimal costUsd, long usedMinutes, BigDecimal sharePercent) {

        public boolean others() {
            return instanceId == null;
        }
    }

    /**
     * 비용 큰 순 상위 3대 + 기타(나머지 합). 단가를 모르는 서버(비용 null)는 빼고, 합계가 0이면 빈 목록.
     */
    public static List<Share> topServers(List<InstanceCost> instances) {
        List<InstanceCost> priced = instances.stream()
            .filter(c -> c.estimatedCost() != null)
            .sorted(Comparator.comparing(InstanceCost::estimatedCost).reversed().thenComparing(InstanceCost::instanceId))
            .toList();
        BigDecimal total = priced.stream().map(InstanceCost::estimatedCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Share> result = new ArrayList<>();
        if (total.signum() == 0) {
            return result;
        }
        BigDecimal othersCost = BigDecimal.ZERO;
        long othersMinutes = 0;
        for (int i = 0; i < priced.size(); i++) {
            InstanceCost c = priced.get(i);
            if (i < TOP_SERVERS) {
                result.add(new Share(c.instanceId(), c.estimatedCost(), c.usedMinutes(), share(c.estimatedCost(), total)));
            } else {
                othersCost = othersCost.add(c.estimatedCost());
                othersMinutes += c.usedMinutes();
            }
        }
        if (priced.size() > TOP_SERVERS) {
            result.add(new Share(null, othersCost, othersMinutes, share(othersCost, total)));
        }
        return List.copyOf(result);
    }

    /** 그 달 리포트를 계산하는 마지막 날: 이번 달이면 오늘, 지난 달이면 말일 */
    public static LocalDate lastDay(YearMonth month, LocalDate today) {
        LocalDate end = month.atEndOfMonth();
        return end.isAfter(today) ? today : end;
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }
}
