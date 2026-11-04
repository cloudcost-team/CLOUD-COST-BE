package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.Percentile;
import com.cloudcost.domain.benchmark.TeamBenchmark;
import com.cloudcost.domain.benchmark.TeamBenchmark.Position;
import com.cloudcost.domain.benchmark.TeamBenchmark.Ratio;
import com.cloudcost.domain.benchmark.TeamBenchmark.ServerVerdict;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeamBenchmarkTest {

    private static Ec2Instance instance(String id) {
        return new Ec2Instance(id, "name-" + id, "m5.large", "ap-northeast-2", "ap-northeast-2a", "running",
            Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
    }

    private static UtilizationStats stats(String id, int hours, String p95) {
        return new UtilizationStats(id, hours, new BigDecimal(p95), new BigDecimal(p95), new BigDecimal(p95), 0, null);
    }

    private static WasteFinding finding(String id, WasteKind kind) {
        return new WasteFinding(instance(id), kind, null, BigDecimal.valueOf(730), null, BigDecimal.ONE, BigDecimal.TEN,
            null, null, null, BigDecimal.ONE);
    }

    private static final List<Ec2Instance> INSTANCES =
        List.of(instance("i-a"), instance("i-b"), instance("i-c"), instance("i-d"), instance("i-e"));
    private static final Map<String, UtilizationStats> STATS = Map.of(
        "i-a", stats("i-a", 336, "6.5"),
        "i-b", stats("i-b", 336, "0.8"),
        "i-c", stats("i-c", 336, "45"),
        "i-d", stats("i-d", 336, "10.0"),
        "i-e", stats("i-e", 20, "1"));

    @Test
    void 이십사시간_이상_켜진_서버만_판단하고_P95_10퍼센트_미만을_과다_사양으로_센다() {
        List<ServerVerdict> verdicts = TeamBenchmark.verdicts(INSTANCES, STATS);

        assertThat(verdicts).extracting(v -> v.instance().instanceId()).as("P95 낮은 순, i-e는 20시간이라 제외")
            .containsExactly("i-b", "i-a", "i-d", "i-c");
        assertThat(verdicts).extracting(ServerVerdict::oversized).containsExactly(true, true, false, false);
        assertThat(verdicts.get(2).verdictLabel()).as("정확히 10%는 적정").isEqualTo("적정");

        Ratio ratio = TeamBenchmark.ratio(verdicts);
        assertThat(ratio.judgedCount()).isEqualTo(4);
        assertThat(ratio.oversizedCount()).isEqualTo(2);
        assertThat(ratio.ratioPercent()).isEqualByComparingTo("50.0");
        assertThat(TeamBenchmark.ratio(List.of()).ratioPercent()).isNull();
    }

    @Test
    void 추천을_적용하면_사양_변경은_적정이_되고_중지는_판단에서_빠진다() {
        List<ServerVerdict> verdicts = TeamBenchmark.verdicts(INSTANCES, STATS);

        Ratio after = TeamBenchmark.ratioAfter(verdicts,
            List.of(finding("i-a", WasteKind.OVERSIZED), finding("i-b", WasteKind.IDLE)));

        assertThat(after.judgedCount()).isEqualTo(3);
        assertThat(after.oversizedCount()).isZero();
        assertThat(after.ratioPercent()).isEqualByComparingTo("0.0");
    }

    @Test
    void 백분위_표가_있으면_우리보다_효율적인_팀_비율을_직선으로_잇고_없으면_null이다() {
        List<Percentile> table = List.of(
            new Percentile(new BigDecimal("25"), new BigDecimal("20")),
            new Percentile(new BigDecimal("50"), new BigDecimal("35")),
            new Percentile(new BigDecimal("75"), new BigDecimal("60")));

        assertThat(TeamBenchmark.moreEfficientTeamsPercent(new BigDecimal("35"), table)).isEqualByComparingTo("50.0");
        assertThat(TeamBenchmark.moreEfficientTeamsPercent(new BigDecimal("47.5"), table)).isEqualByComparingTo("62.5");
        assertThat(TeamBenchmark.moreEfficientTeamsPercent(new BigDecimal("10"), table)).isEqualByComparingTo("0");
        assertThat(TeamBenchmark.moreEfficientTeamsPercent(new BigDecimal("80"), table)).isEqualByComparingTo("100");
        assertThat(TeamBenchmark.moreEfficientTeamsPercent(new BigDecimal("35"), List.of())).isNull();
        assertThat(TeamBenchmark.moreEfficientTeamsPercent(null, table)).isNull();
    }

    @Test
    void 월_지출은_범위_아래_안_위로_나눈다() {
        BenchmarkReference.Spend range =
            new BenchmarkReference.Spend("s", "d", BigDecimal.valueOf(100), BigDecimal.valueOf(500));

        assertThat(TeamBenchmark.position(new BigDecimal("99.99"), range)).isEqualTo(Position.BELOW);
        assertThat(TeamBenchmark.position(new BigDecimal("100"), range)).isEqualTo(Position.WITHIN);
        assertThat(TeamBenchmark.position(new BigDecimal("500"), range)).isEqualTo(Position.WITHIN);
        assertThat(TeamBenchmark.position(new BigDecimal("500.01"), range)).isEqualTo(Position.ABOVE);
    }
}
