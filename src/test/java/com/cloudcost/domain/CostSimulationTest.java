package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.simulation.CostSimulation;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostSimulationTest {

    private static WasteFinding finding(String id, WasteKind kind, String savings) {
        Ec2Instance i = new Ec2Instance(id, id, "m5.large", "ap-northeast-2", "ap-northeast-2a", "running",
            Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
        return new WasteFinding(i, kind, null, BigDecimal.valueOf(730), null, BigDecimal.ONE, BigDecimal.TEN,
            null, null, null, new BigDecimal(savings), null);
    }

    private static final List<WasteFinding> FINDINGS =
        List.of(finding("i-a", WasteKind.IDLE, "40"), finding("i-b", WasteKind.OVERSIZED, "20"));

    @Test
    void 적용_전후는_월말_예상에서_절감액을_빼고_트래픽_증가율을_곱한다() {
        CostSimulation s = CostSimulation.of(new BigDecimal("200"), FINDINGS, 50, new BigDecimal("250"));

        assertThat(s.selectedSavingsUsd()).isEqualByComparingTo("60");
        assertThat(s.beforeUsd()).isEqualByComparingTo("300");
        assertThat(s.afterUsd()).as("(200 − 60) × 1.5").isEqualByComparingTo("210");
        assertThat(s.monthlySavingsUsd()).isEqualByComparingTo("90");
        assertThat(s.yearlySavingsUsd()).isEqualByComparingTo("1080");
        assertThat(s.withinBudget()).isTrue();
        assertThat(s.beforeBudgetRate()).isEqualByComparingTo("120.0");
        assertThat(s.afterBudgetRate()).isEqualByComparingTo("84.0");
    }

    @Test
    void 절감액이_월말_예상보다_크면_적용_후는_0이고_예산이_없으면_비교하지_않는다() {
        CostSimulation s = CostSimulation.of(new BigDecimal("50"), FINDINGS, 0, null);

        assertThat(s.afterUsd()).isEqualByComparingTo("0");
        assertThat(s.monthlySavingsUsd()).isEqualByComparingTo("50");
        assertThat(s.withinBudget()).isNull();
        assertThat(s.afterBudgetRate()).isNull();
    }

    @Test
    void 예산을_넘으면_withinBudget은_false다() {
        assertThat(CostSimulation.of(new BigDecimal("200"), List.of(), 0, new BigDecimal("199.99")).withinBudget())
            .isFalse();
        assertThat(CostSimulation.of(new BigDecimal("200"), List.of(), 0, new BigDecimal("200")).withinBudget())
            .as("예산과 같으면 안").isTrue();
    }

    @Test
    void 추천_id는_목록에서_찾고_중복은_한_번만_모르는_id는_거절한다() {
        assertThat(CostSimulation.select(FINDINGS, List.of("i-b:OVERSIZED", "i-a:IDLE", "i-b:OVERSIZED")))
            .extracting(WasteFinding::recommendationId).containsExactly("i-b:OVERSIZED", "i-a:IDLE");
        assertThat(CostSimulation.select(FINDINGS, null)).isEmpty();

        assertThatThrownBy(() -> CostSimulation.select(FINDINGS, List.of("i-a:IDLE", "i-a:OVERSIZED")))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("i-a:OVERSIZED")
            .extracting(e -> ((DomainValidationException) e).code()).isEqualTo("UNKNOWN_RECOMMENDATION");
        assertThatThrownBy(() -> CostSimulation.select(FINDINGS, Arrays.asList("i-a:IDLE", null)))
            .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void 트래픽_증가율은_0에서_100까지만_받는다() {
        assertThatThrownBy(() -> CostSimulation.of(BigDecimal.TEN, List.of(), 101, null))
            .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> CostSimulation.requireValidGrowth(-1)).isInstanceOf(DomainValidationException.class);
        assertThat(CostSimulation.of(BigDecimal.TEN, List.of(), 100, null).beforeUsd()).isEqualByComparingTo("20");
    }
}
