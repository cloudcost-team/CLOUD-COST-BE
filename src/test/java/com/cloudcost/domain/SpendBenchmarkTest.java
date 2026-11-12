package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.UsagePercentile;
import com.cloudcost.domain.benchmark.SpendBenchmark;
import com.cloudcost.domain.benchmark.SpendBenchmark.UsdRange;
import com.cloudcost.domain.benchmark.TeamBenchmark.Position;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.fakes.Fixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpendBenchmarkTest {

    private static final Instant LAUNCH = Instant.parse("2026-09-01T00:00:00Z");

    private static UtilizationStats hours(String id, int cpuHours) {
        return new UtilizationStats(id, cpuHours, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, null);
    }

    private static List<UsagePercentile> table(String... pairs) {
        return java.util.Arrays.stream(pairs).map(p -> p.split(":"))
            .map(p -> new UsagePercentile(new BigDecimal(p[0]), new BigDecimal(p[1]))).toList();
    }

    @Test
    void vCPU는_비교_자료의_코어_구간_대표값으로_맞춘다() {
        assertThat(SpendBenchmark.vcpuBucket(1)).isEqualTo(2);
        assertThat(SpendBenchmark.vcpuBucket(2)).isEqualTo(2);
        assertThat(SpendBenchmark.vcpuBucket(3)).isEqualTo(4);
        assertThat(SpendBenchmark.vcpuBucket(8)).isEqualTo(8);
        assertThat(SpendBenchmark.vcpuBucket(16)).isEqualTo(24);
        assertThat(SpendBenchmark.vcpuBucket(48)).isEqualTo(32);
    }

    @Test
    void 한_달_사용량은_14일_켜진_시간을_30일로_환산하고_꺼진_서버와_모르는_타입은_뺀다() {
        var usage = SpendBenchmark.monthlyUsage(
            List.of(
                Fixtures.instance("i-a", "t3.small", "running", LAUNCH),
                Fixtures.instance("i-b", "m5.xlarge", "running", LAUNCH),
                Fixtures.instance("i-off", "t3.small", "stopped", LAUNCH),
                Fixtures.instance("i-x", "x9.unknown", "running", LAUNCH)),
            Map.of("i-a", hours("i-a", 336), "i-b", hours("i-b", 168), "i-off", hours("i-off", 0),
                "i-x", hours("i-x", 336)));

        assertThat(usage.servers()).isEqualTo(2);
        assertThat(usage.vcpuHours()).as("(2×336 + 4×168) × 30 ÷ 14").isEqualByComparingTo("2880.0");
        assertThat(usage.unknownTypes()).containsExactly("x9.unknown");
    }

    @Test
    void 우리보다_확실히_적게_쓰는_팀은_같은_사용량을_넣지_않는다() {
        List<UsagePercentile> t = table("25:562.2", "50:1440", "60:1773", "65:2880", "75:2880");

        assertThat(SpendBenchmark.teamsUsingLessPercent(new BigDecimal("1440"), t)).isEqualByComparingTo("25.0");
        assertThat(SpendBenchmark.teamsUsingLessPercent(new BigDecimal("2880"), t)).isEqualByComparingTo("60.0");
        assertThat(SpendBenchmark.teamsUsingLessPercent(new BigDecimal("10"), t)).isEqualByComparingTo("0.0");
        assertThat(SpendBenchmark.teamsUsingLessPercent(new BigDecimal("10"), List.of())).isNull();
    }

    @Test
    void 일반_범위는_가운데_절반_사용량을_우리_vCPU_시간당_비용으로_환산한다() {
        List<UsagePercentile> t = table("25:720", "50:1440", "75:2880");

        UsdRange range = SpendBenchmark.typicalRange(t, new BigDecimal("36"), new BigDecimal("1440")).orElseThrow();

        assertThat(range.minUsd()).as("720 × 36 ÷ 1440").isEqualByComparingTo("18");
        assertThat(range.maxUsd()).isEqualByComparingTo("72");
        assertThat(SpendBenchmark.typicalRange(t, new BigDecimal("36"), BigDecimal.ZERO)).isEmpty();
    }

    @Test
    void 분위_회귀_모델은_서버_대수로_백분위_표를_예측하고_순서가_뒤집히면_바로잡는다() {
        // ln(사용량) = c0 + c1·ln(s) + c2·ln(s)². c1 = 1이면 서버 수에 비례한다
        var model = new BenchmarkReference.SpendModel(1, 50, 100,
            new BenchmarkReference.Validation(80, 20, new BigDecimal("0.3"), new BigDecimal("0.4"), "b", new BigDecimal("50")),
            List.of(q(25, Math.log(800), 1), q(50, Math.log(1440), 1), q(75, Math.log(1000), 1)));

        List<UsagePercentile> two = model.table(2).orElseThrow();

        assertThat(two).extracting(UsagePercentile::vcpuHours).map(BigDecimal::toPlainString)
            .as("75번째 예측(2000)이 50번째(2880)보다 작아 정렬로 바로잡는다").containsExactly("1600.0", "2000.0", "2880.0");
        assertThat(model.table(51)).isEmpty();
        assertThat(model.validation().improvementPercent()).isEqualByComparingTo("25.0");
    }

    private static BenchmarkReference.QuantileCoefficients q(int percentile, double c0, double c1) {
        return new BenchmarkReference.QuantileCoefficients(BigDecimal.valueOf(percentile),
            List.of(BigDecimal.valueOf(c0), BigDecimal.valueOf(c1), BigDecimal.ZERO));
    }

    @Test
    void 범위_막대_위치는_하한_0_상한_100이고_밖이면_끝에_붙는다() {
        UsdRange range = new UsdRange(BigDecimal.valueOf(100), BigDecimal.valueOf(500));

        assertThat(SpendBenchmark.barPositionPercent(new BigDecimal("197.904"), range)).isEqualByComparingTo("24.5");
        assertThat(SpendBenchmark.barPositionPercent(BigDecimal.valueOf(50), range)).isEqualByComparingTo("0");
        assertThat(SpendBenchmark.barPositionPercent(BigDecimal.valueOf(900), range)).isEqualByComparingTo("100");
    }

    @Test
    void 월_지출은_범위_아래_안_위로_나눈다() {
        UsdRange range = new UsdRange(BigDecimal.valueOf(100), BigDecimal.valueOf(500));

        assertThat(SpendBenchmark.position(new BigDecimal("99.99"), range)).isEqualTo(Position.BELOW);
        assertThat(SpendBenchmark.position(new BigDecimal("100"), range)).isEqualTo(Position.WITHIN);
        assertThat(SpendBenchmark.position(new BigDecimal("500"), range)).isEqualTo(Position.WITHIN);
        assertThat(SpendBenchmark.position(new BigDecimal("500.01"), range)).isEqualTo(Position.ABOVE);
    }
}
