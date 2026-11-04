package com.cloudcost.domain.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;

/**
 * 월말 예상 비용 = 어제까지 이번 달 추정 비용 + 최근 7일(어제까지, 기록 시작일 이후) 하루 평균 × 오늘부터 말일까지 남은 날.
 *
 * <p>오늘은 아직 끝나지 않아 실제 값 대신 평균으로 센다. 기록이 7일보다 짧으면 있는 날로 평균을 내고,
 * 끝난 날이 하루도 없으면 예상하지 않는다. 금액은 USD다.
 *
 * @param costThroughYesterdayUsd 이번 달 1일 ~ 어제 추정 비용
 * @param dailyAverageUsd         평균에 쓴 하루 추정 비용
 * @param basisDays               평균에 쓴 날 수 (1~7)
 * @param remainingDays           오늘을 포함해 말일까지 남은 날
 */
public record MonthEndForecast(
    BigDecimal monthEndUsd,
    BigDecimal costThroughYesterdayUsd,
    BigDecimal dailyAverageUsd,
    int basisDays,
    LocalDate basisFrom,
    LocalDate basisTo,
    int remainingDays) {

    public static final int BASIS_DAYS = 7;
    public static final String METHOD =
        "월말 예상 = 어제까지 이번 달 추정 비용 + 최근 7일 하루 평균 × 오늘부터 남은 날 (지금 사용 속도가 이어진다고 가정)";

    /**
     * @param dailyUsd      날짜 → 그날 추정 비용 (USD). 없는 날은 0으로 본다
     * @param availableFrom 기록 시작일. null이면 기록이 없다
     */
    public static Optional<MonthEndForecast> of(
        Map<LocalDate, BigDecimal> dailyUsd, LocalDate today, LocalDate availableFrom) {
        if (availableFrom == null) {
            return Optional.empty();
        }
        LocalDate yesterday = today.minusDays(1);
        LocalDate basisFrom = latest(today.minusDays(BASIS_DAYS), availableFrom);
        if (basisFrom.isAfter(yesterday)) {
            return Optional.empty();
        }
        int basisDays = 0;
        BigDecimal basisSum = BigDecimal.ZERO;
        for (LocalDate d = basisFrom; !d.isAfter(yesterday); d = d.plusDays(1)) {
            basisSum = basisSum.add(dailyUsd.getOrDefault(d, BigDecimal.ZERO));
            basisDays++;
        }
        BigDecimal average = basisSum.divide(BigDecimal.valueOf(basisDays), 6, RoundingMode.HALF_UP);

        BigDecimal toDate = BigDecimal.ZERO;
        for (LocalDate d = today.withDayOfMonth(1); d.isBefore(today); d = d.plusDays(1)) {
            toDate = toDate.add(dailyUsd.getOrDefault(d, BigDecimal.ZERO));
        }
        int remaining = YearMonth.from(today).lengthOfMonth() - today.getDayOfMonth() + 1;
        BigDecimal monthEnd = toDate.add(average.multiply(BigDecimal.valueOf(remaining)));
        return Optional.of(new MonthEndForecast(
            monthEnd.setScale(6, RoundingMode.HALF_UP), toDate, average, basisDays, basisFrom, yesterday, remaining));
    }

    /** 예상 대비 예산 사용률 (%). 소수 첫째 자리 */
    public BigDecimal budgetRate(BigDecimal budgetUsd) {
        if (budgetUsd == null || budgetUsd.signum() <= 0) {
            return null;
        }
        return monthEndUsd.multiply(BigDecimal.valueOf(100)).divide(budgetUsd, 1, RoundingMode.HALF_UP);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
