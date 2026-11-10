package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.budget.BudgetSlider;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BudgetSliderTest {

    @Test
    void 최대는_평균의_2배_예상의_1_5배_예산의_1_5배_중_큰_값을_단위로_올린다() {
        BudgetSlider s = BudgetSlider.of(Currency.KRW, new BigDecimal("286000"), new BigDecimal("312000"),
            new BigDecimal("400000"));

        assertThat(s.step()).isEqualByComparingTo("10000");
        assertThat(s.min()).isEqualByComparingTo("10000");
        assertThat(s.max()).as("max(572000, 468000, 600000)").isEqualByComparingTo("600000");
        assertThat(s.suggested()).as("지금 예산").isEqualByComparingTo("400000");
    }

    @Test
    void 예산이_없으면_평균_그것도_없으면_예상에서_시작하고_근거가_없으면_기본_범위다() {
        BudgetSlider avg = BudgetSlider.of(Currency.USD, new BigDecimal("123.4"), null, null);
        assertThat(avg.step()).isEqualByComparingTo("10");
        assertThat(avg.max()).as("246.8 → 250").isEqualByComparingTo("250");
        assertThat(avg.suggested()).as("123.4 → 130").isEqualByComparingTo("130");

        BudgetSlider forecast = BudgetSlider.of(Currency.USD, null, new BigDecimal("31"), null);
        assertThat(forecast.max()).as("46.5 → 50").isEqualByComparingTo("50");
        assertThat(forecast.suggested()).isEqualByComparingTo("40");

        BudgetSlider none = BudgetSlider.of(Currency.KRW, null, null, null);
        assertThat(none.max()).isEqualByComparingTo("1000000");
        assertThat(none.suggested()).isEqualByComparingTo("500000");
        assertThat(BudgetSlider.of(Currency.USD, null, BigDecimal.ZERO, null).max()).isEqualByComparingTo("1000");
    }

    @Test
    void 사용_근거가_없으면_예산을_저장해도_기본_최대_아래로_좁아지지_않는다() {
        BudgetSlider krw = BudgetSlider.of(Currency.KRW, null, null, new BigDecimal("20000"));
        assertThat(krw.max()).as("max(30000, 기본 100만)").isEqualByComparingTo("1000000");
        assertThat(krw.suggested()).isEqualByComparingTo("20000");

        BudgetSlider usd = BudgetSlider.of(Currency.USD, null, BigDecimal.ZERO, new BigDecimal("20"));
        assertThat(usd.max()).isEqualByComparingTo("1000");

        BudgetSlider big = BudgetSlider.of(Currency.KRW, null, null, new BigDecimal("2000000"));
        assertThat(big.max()).as("예산 × 1.5가 기본보다 크면 그쪽").isEqualByComparingTo("3000000");

        BudgetSlider withUsage = BudgetSlider.of(Currency.KRW, new BigDecimal("10000"), null, new BigDecimal("20000"));
        assertThat(withUsage.max()).as("사용 근거가 있으면 기본값을 쓰지 않는다").isEqualByComparingTo("30000");
    }
}
