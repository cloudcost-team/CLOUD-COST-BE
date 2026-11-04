package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.budget.MonthEndForecast;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BudgetDomainTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    private static Map<LocalDate, BigDecimal> everyDay(LocalDate from, LocalDate to, String usd) {
        Map<LocalDate, BigDecimal> map = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            map.put(d, new BigDecimal(usd));
        }
        return map;
    }

    @Test
    void 월말_예상은_어제까지_비용에_최근_7일_평균_곱하기_남은_날을_더한다() {
        // 10/29~11/4 하루 $10, 11/1~11/4 = $40, 남은 날 11/5~11/30 = 26일
        MonthEndForecast f = MonthEndForecast.of(
                everyDay(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 4), "10"), NOV_5,
                LocalDate.of(2026, 8, 1)).orElseThrow();

        assertThat(f.costThroughYesterdayUsd()).isEqualByComparingTo("40");
        assertThat(f.dailyAverageUsd()).isEqualByComparingTo("10");
        assertThat(f.basisDays()).isEqualTo(7);
        assertThat(f.basisFrom()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(f.remainingDays()).isEqualTo(26);
        assertThat(f.monthEndUsd()).isEqualByComparingTo("300");
        assertThat(f.budgetRate(new BigDecimal("400"))).isEqualByComparingTo("75.0");
    }

    @Test
    void 기록이_7일보다_짧으면_있는_날로_평균을_내고_끝난_날이_없으면_예상하지_않는다() {
        Map<LocalDate, BigDecimal> daily = everyDay(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4), "6");

        MonthEndForecast f = MonthEndForecast.of(daily, NOV_5, LocalDate.of(2026, 11, 3)).orElseThrow();
        assertThat(f.basisDays()).isEqualTo(2);
        assertThat(f.monthEndUsd()).as("12 + 6 × 26").isEqualByComparingTo("168");

        assertThat(MonthEndForecast.of(daily, NOV_5, NOV_5)).as("오늘 처음 기록").isEmpty();
        assertThat(MonthEndForecast.of(daily, NOV_5, null)).isEmpty();
    }

    @Test
    void 매달_1일에는_전월_마지막_7일로_평균을_낸다() {
        MonthEndForecast f = MonthEndForecast.of(
                everyDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31), "5"), LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 8, 1)).orElseThrow();

        assertThat(f.costThroughYesterdayUsd()).isZero();
        assertThat(f.monthEndUsd()).as("5 × 30일").isEqualByComparingTo("150");
    }

    @Test
    void 예산은_입력한_통화_단위로_다듬고_잘못된_값은_거절한다() {
        assertThat(new MonthlyBudget(new BigDecimal("400000.4"), Currency.KRW).amount()).isEqualByComparingTo("400000");
        assertThat(new MonthlyBudget(new BigDecimal("300.456"), Currency.USD).amount()).isEqualByComparingTo("300.46");

        assertThatThrownBy(() -> new MonthlyBudget(BigDecimal.ZERO, Currency.KRW))
                .isInstanceOf(DomainValidationException.class).hasMessageContaining("0보다 큰 숫자");
        assertThatThrownBy(() -> new MonthlyBudget(new BigDecimal("0.4"), Currency.KRW))
                .as("원 단위로 반올림하면 0").isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new MonthlyBudget(new BigDecimal("1000000000001"), Currency.KRW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new MonthlyBudget(BigDecimal.TEN, null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void 원화_예산은_환율로_달러로_바꿔_비교하고_환율이_없으면_비교하지_않는다() {
        MonthlyBudget krw = new MonthlyBudget(new BigDecimal("390000"), Currency.KRW);
        ExchangeRate rate = new ExchangeRate(new BigDecimal("1300"), LocalDate.of(2026, 11, 4), "test", Instant.EPOCH);

        assertThat(krw.toUsd(rate)).isEqualByComparingTo("300");
        assertThat(krw.toUsd(null)).isNull();
        assertThat(new MonthlyBudget(BigDecimal.TEN, Currency.USD).toUsd(null)).isEqualByComparingTo("10");
    }
}
