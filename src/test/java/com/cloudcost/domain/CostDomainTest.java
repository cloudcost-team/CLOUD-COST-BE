package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.cost.PriceQuery;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.fakes.Fixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostDomainTest {

    private static final BigDecimal T3_MEDIUM = new BigDecimal("0.0416");

    @Test
    void 시간당_비용은_사용_시간_곱하기_온디맨드_단가() {
        assertThat(CostEstimator.estimate(84 * 60, T3_MEDIUM)).isEqualByComparingTo("3.4944");
        assertThat(CostEstimator.estimate(30, T3_MEDIUM)).isEqualByComparingTo("0.0208");
    }

    @Test
    void 단가를_찾지_못하면_비용은_0이_아니라_null() {
        assertThat(CostEstimator.estimate(600, null)).isNull();
        InstancePrice price = InstancePrice.of("i-a", null, false);
        assertThat(price.priced()).isFalse();
        assertThat(price.note()).isEqualTo(CostNotes.PRICE_NOT_FOUND);
        assertThat(InstancePrice.of("i-a", BigDecimal.ZERO, false).priced()).as("0원 단가도 없는 것으로 본다").isFalse();
    }

    @Test
    void Spot_인스턴스는_온디맨드_단가로_계산하고_할인_미반영을_알린다() {
        InstancePrice spot = InstancePrice.of("i-s", T3_MEDIUM, true);
        assertThat(spot.hourlyUsd()).isEqualByComparingTo(T3_MEDIUM);
        assertThat(spot.note()).isEqualTo(CostNotes.SPOT);

        Ec2Instance instance = Fixtures.spot("i-s", "t3.medium", "running", null);
        assertThat(PriceQuery.forInstance(instance)).hasValueSatisfying(q -> {
            assertThat(q.operatingSystem()).isEqualTo("Linux");
            assertThat(q.tenancy()).isEqualTo("Shared");
            assertThat(q.licenseModel()).isEqualTo("No License required");
            assertThat(q.preInstalledSw()).isEqualTo("NA");
        });
    }

    @Test
    void OS와_테넌시를_Price_List_속성으로_바꾼다() {
        Ec2Instance windowsSql = new Ec2Instance("i-w", null, "m5.large", "ap-northeast-2", null, "running", null,
                "Windows with SQL Server Standard", "dedicated", false, false);
        assertThat(PriceQuery.forInstance(windowsSql)).hasValueSatisfying(q -> {
            assertThat(q.operatingSystem()).isEqualTo("Windows");
            assertThat(q.preInstalledSw()).isEqualTo("SQL Std");
            assertThat(q.tenancy()).isEqualTo("Dedicated");
        });
        Ec2Instance unknown = new Ec2Instance("i-u", null, "m5.large", "ap-northeast-2", null, "running", null,
                "Some Future OS", "default", false, false);
        assertThat(PriceQuery.forInstance(unknown)).isEmpty();
    }

    @Test
    void 일별_비용_집계() {
        Map<LocalDate, Map<String, Long>> minutes = new LinkedHashMap<>();
        minutes.put(LocalDate.of(2026, 10, 1), Map.of("i-a", 8 * 60L));
        minutes.put(LocalDate.of(2026, 10, 2), Map.of("i-a", 12 * 60L));
        minutes.put(LocalDate.of(2026, 10, 3), Map.of());
        CostReport<LocalDate> report = CostEstimator.report(minutes, Map.of("i-a", InstancePrice.of("i-a", T3_MEDIUM, false)));

        assertThat(report.periods()).hasSize(3);
        assertThat(report.periods().get(0).estimatedCost()).isEqualByComparingTo("0.3328");
        assertThat(report.periods().get(1).estimatedCost()).isEqualByComparingTo("0.4992");
        assertThat(report.periods().get(2).usedMinutes()).isZero();
        assertThat(report.periods().get(2).estimatedCost()).isEqualByComparingTo("0");
        assertThat(report.totalMinutes()).isEqualTo(20 * 60);
        assertThat(report.totalEstimatedCost()).isEqualByComparingTo("0.8320");
    }

    @Test
    void 월별_비용_집계와_단가_없는_인스턴스_분리() {
        Map<YearMonth, Map<String, Long>> minutes = new LinkedHashMap<>();
        minutes.put(YearMonth.of(2026, 9), Map.of("i-a", 100 * 60L, "i-x", 10 * 60L));
        minutes.put(YearMonth.of(2026, 10), Map.of("i-x", 5 * 60L));
        Map<String, InstancePrice> prices = Map.of(
                "i-a", InstancePrice.of("i-a", T3_MEDIUM, false),
                "i-x", InstancePrice.of("i-x", null, false));
        CostReport<YearMonth> report = CostEstimator.report(minutes, prices);

        assertThat(report.periods().get(0).estimatedCost()).as("단가 있는 인스턴스만 합산").isEqualByComparingTo("4.16");
        assertThat(report.periods().get(1).estimatedCost()).as("사용은 있는데 단가가 하나도 없으면 null").isNull();
        assertThat(report.totalEstimatedCost()).isEqualByComparingTo("4.16");
        assertThat(report.unpricedInstanceIds()).containsExactly("i-x");
        assertThat(report.unpricedMinutes()).isEqualTo(15 * 60);
    }
}
