package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.benchmark.WasteRatio;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WasteRatioTest {

    @Test
    void 방치_리소스_비용은_분자와_분모에_모두_더한다() {
        WasteRatio r = WasteRatio.of(new BigDecimal("20"), new BigDecimal("30"), new BigDecimal("70"));
        assertThat(r.wasteUsd()).isEqualByComparingTo("50");
        assertThat(r.totalUsd()).isEqualByComparingTo("100");
        assertThat(r.ratioPercent()).isEqualByComparingTo("50.0");
        assertThat(r.compareTo(new BigDecimal("29"))).isEqualTo(WasteRatio.Comparison.ABOVE);
    }

    @Test
    void 월말_예상보다_서버_낭비가_커도_100퍼센트를_넘지_않고_전체가_0이면_null() {
        assertThat(WasteRatio.of(new BigDecimal("150"), BigDecimal.ZERO, new BigDecimal("100")).ratioPercent())
                .isEqualByComparingTo("100");
        WasteRatio empty = WasteRatio.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(empty.ratioPercent()).isNull();
        assertThat(empty.compareTo(new BigDecimal("29"))).isNull();
        assertThat(WasteRatio.of(new BigDecimal("-5"), null, new BigDecimal("10")).ratioPercent()).isEqualByComparingTo("0");
    }
}
