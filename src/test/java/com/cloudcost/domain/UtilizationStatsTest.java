package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.UtilizationStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UtilizationStatsTest {

    private static final Instant H0 = Instant.parse("2026-10-01T00:00:00Z");

    private static HourlySample hour(String id, int h, double sampleCount, double avg, double max, Long in, Long out) {
        return new HourlySample(id, H0.plusSeconds(3600L * h), BigDecimal.valueOf(sampleCount),
                BigDecimal.valueOf(avg), BigDecimal.valueOf(max), in, out);
    }

    @Test
    void CPU_평균은_켜져_있던_분으로_가중_평균한다() {
        UtilizationStats stats = UtilizationStats.byInstance(List.of(
                hour("i-a", 0, 60, 10, 20, null, null),
                hour("i-a", 1, 20, 50, 90, null, null))).get("i-a");

        // (10×60 + 50×20) / 80 = 20
        assertThat(stats.cpuAvg()).isEqualByComparingTo("20.00");
        assertThat(stats.cpuMax()).isEqualByComparingTo("90.00");
        assertThat(stats.cpuHours()).isEqualTo(2);
    }

    @Test
    void P95는_시간별_평균의_95번째_백분위다() {
        List<HourlySample> samples = new ArrayList<>();
        for (int h = 1; h <= 100; h++) {
            samples.add(hour("i-a", h, 60, h, h, null, null));   // 평균 1%~100%
        }

        UtilizationStats stats = UtilizationStats.byInstance(samples).get("i-a");

        assertThat(stats.cpuP95()).as("nearest-rank: 100개 중 95번째").isEqualByComparingTo("95.00");
        assertThat(stats.cpuAvg()).isEqualByComparingTo("50.50");
    }

    @Test
    void 네트워크는_입출력을_더하고_값이_없는_시간은_세지_않는다() {
        UtilizationStats stats = UtilizationStats.byInstance(List.of(
                hour("i-a", 0, 60, 1, 2, 100L, 200L),
                hour("i-a", 1, 60, 1, 2, 50L, null),
                hour("i-a", 2, 60, 1, 2, null, null))).get("i-a");

        assertThat(stats.networkBytes()).isEqualTo(350L);
        assertThat(stats.networkHours()).isEqualTo(2);
    }

    @Test
    void 지표가_없는_예전_행만_있으면_CPU와_네트워크는_null이다() {
        Map<String, UtilizationStats> stats = UtilizationStats.byInstance(List.of(
                new HourlySample("i-old", H0, BigDecimal.valueOf(60))));

        UtilizationStats old = stats.get("i-old");
        assertThat(old.cpuHours()).isZero();
        assertThat(old.cpuAvg()).isNull();
        assertThat(old.cpuP95()).isNull();
        assertThat(old.cpuMax()).isNull();
        assertThat(old.networkBytes()).isNull();
    }

    @Test
    void 인스턴스별로_나눠_계산한다() {
        Map<String, UtilizationStats> stats = UtilizationStats.byInstance(List.of(
                hour("i-a", 0, 60, 5, 9, null, null),
                hour("i-b", 0, 60, 70, 99, null, null)));

        assertThat(stats).containsOnlyKeys("i-a", "i-b");
        assertThat(stats.get("i-b").cpuP95()).isEqualByComparingTo("70.00");
    }
}
