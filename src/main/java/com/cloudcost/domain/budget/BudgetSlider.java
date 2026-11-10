package com.cloudcost.domain.budget;

import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Stream;

/**
 * 월 예산 수정 화면의 슬라이더 범위 (기능명세서 F08-1, 이슈 #41). 금액은 화면 통화 기준이다.
 *
 * <ul>
 *   <li>단위(step): 원화 1만 원, 달러 10달러
 *   <li>최소: 한 단위
 *   <li>최대: 지난 3개월 평균 × 2, 월말 예상 × 1.5, 지금 예산 × 1.5 중 가장 큰 값을 단위로 올림.
 *       사용 근거(평균·예상)가 없으면 기본값(원화 100만 원 · 달러 1,000달러) 아래로 내려가지 않는다.
 *       지금 예산만으로 범위를 정하면 저장할 때마다 범위가 좁아지기 때문이다.
 *   <li>처음 위치(suggested): 지금 예산, 없으면 지난 3개월 평균, 없으면 월말 예상 (단위로 올림). 다 없으면 최대의 절반
 * </ul>
 */
public record BudgetSlider(BigDecimal min, BigDecimal max, BigDecimal step, BigDecimal suggested) {

    static final BigDecimal KRW_STEP = new BigDecimal("10000");
    static final BigDecimal USD_STEP = BigDecimal.TEN;
    static final BigDecimal KRW_DEFAULT_MAX = new BigDecimal("1000000");
    static final BigDecimal USD_DEFAULT_MAX = new BigDecimal("1000");

    /**
     * @param recentAverage 지난 3개월 평균 (없으면 null)
     * @param forecast      월말 예상 (없으면 null)
     * @param budget        지금 예산 (없으면 null)
     */
    public static BudgetSlider of(Currency currency, BigDecimal recentAverage, BigDecimal forecast, BigDecimal budget) {
        BigDecimal step = currency == Currency.KRW ? KRW_STEP : USD_STEP;
        BigDecimal defaultMax = currency == Currency.KRW ? KRW_DEFAULT_MAX : USD_DEFAULT_MAX;
        boolean hasUsage = positive(recentAverage) || positive(forecast);
        BigDecimal max = Stream.of(
                times(recentAverage, "2"), times(forecast, "1.5"), times(budget, "1.5"),
                hasUsage ? null : defaultMax)
            .filter(BudgetSlider::positive)
            .max(BigDecimal::compareTo)
            .map(v -> roundUp(v, step))
            .orElse(defaultMax);
        BigDecimal suggested = Stream.of(budget, recentAverage, forecast)
            .filter(BudgetSlider::positive)
            .findFirst()
            .map(v -> roundUp(v, step))
            .orElse(roundUp(max.divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP), step));
        return new BudgetSlider(step, max.max(step), step, suggested.min(max).max(step));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal times(BigDecimal value, String factor) {
        return value == null ? null : value.multiply(new BigDecimal(factor));
    }

    static BigDecimal roundUp(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }
}
