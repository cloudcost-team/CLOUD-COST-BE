package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.ec2.InstanceSpecs.Spec;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteDetector;
import com.cloudcost.domain.waste.WasteDetector.Candidate;
import com.cloudcost.domain.waste.WasteDetector.Result;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 낭비 탐지 규칙 (#10). 14일 = 336시간 내내 켜져 있으면 월 730시간으로 본다. */
class WasteDetectorTest {

    private static final long ALWAYS_ON = 336L * 60;

    private static Ec2Instance instance(String id, String type) {
        return new Ec2Instance(id, id + "-name", type, "ap-northeast-2", "ap-northeast-2a", "running",
                Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
    }

    private static UtilizationStats stats(String id, int hours, String avg, String p95, String max, Long network) {
        return new UtilizationStats(id, hours, new BigDecimal(avg), new BigDecimal(p95), new BigDecimal(max),
                network == null ? 0 : hours, network);
    }

    private static Candidate candidate(String id, String type, UtilizationStats stats, long minutes, String hourly,
                                       String targetHourly) {
        return new Candidate(instance(id, type), stats, minutes, hourly == null ? null : new BigDecimal(hourly),
                targetHourly == null ? null : new BigDecimal(targetHourly));
    }

    @Test
    void 한_단계_줄인_타입은_t3에서_메모리_절반_이하_vCPU_이하_중_가장_큰_것이다() {
        assertThat(InstanceSpecs.downsizeTarget("m5.large")).map(Spec::instanceType).hasValue("t3.medium");
        assertThat(InstanceSpecs.downsizeTarget("m5.xlarge")).map(Spec::instanceType).hasValue("t3.large");
        assertThat(InstanceSpecs.downsizeTarget("t3.medium")).map(Spec::instanceType).hasValue("t3.small");
        assertThat(InstanceSpecs.downsizeTarget("c5.large")).map(Spec::instanceType).hasValue("t3.small");
        assertThat(InstanceSpecs.downsizeTarget("r5.large")).map(Spec::instanceType).hasValue("t3.large");
        assertThat(InstanceSpecs.downsizeTarget("m5.4xlarge")).map(Spec::instanceType).hasValue("t3.2xlarge");
        assertThat(InstanceSpecs.downsizeTarget("r5.4xlarge")).as("절반(64GiB)인 t3가 없으면 그 아래 가장 큰 t3")
                .map(Spec::instanceType).hasValue("t3.2xlarge");
        assertThat(InstanceSpecs.downsizeTarget("t2.micro")).as("vCPU가 지금보다 많은 t3는 고르지 않는다").isEmpty();
        assertThat(InstanceSpecs.downsizeTarget("t3.nano")).as("더 작은 t3 없음").isEmpty();
        assertThat(InstanceSpecs.downsizeTarget("g4dn.xlarge")).as("GPU는 추천하지 않음").isEmpty();
        assertThat(InstanceSpecs.downsizeTarget("x9.unknown")).isEmpty();
    }

    @Test
    void 과다_사양은_CPU_P95_10퍼센트_미만이고_절감액은_월_비용_차이다() {
        Result result = WasteDetector.detect(List.of(candidate("i-api", "m5.large",
                stats("i-api", 336, "6", "9", "23", 10_000_000_000L), ALWAYS_ON, "0.118", "0.052")));

        WasteFinding f = result.findings().getFirst();
        assertThat(f.kind()).isEqualTo(WasteKind.OVERSIZED);
        assertThat(f.monthlyHours()).isEqualByComparingTo("730");
        assertThat(f.monthlyCostUsd()).isEqualByComparingTo("86.14");
        assertThat(f.targetMonthlyCostUsd()).isEqualByComparingTo("37.96");
        assertThat(f.monthlySavingsUsd()).isEqualByComparingTo("48.18");
        assertThat(f.recommendationId()).isEqualTo("i-api:OVERSIZED");
        assertThat(f.evidence()).isEqualTo("최근 14일 CPU 평균 6% (P95 9%)");
        assertThat(f.action()).isEqualTo("m5.large → t3.medium 으로 변경 검토");
        assertThat(f.consoleSteps()).hasSize(3);
        assertThat(result.judgedCount()).isEqualTo(1);
    }

    @Test
    void 유휴는_CPU_1퍼센트_미만에_네트워크가_적고_절감액은_월_비용_전부다() {
        Result result = WasteDetector.detect(List.of(candidate("i-batch", "c5.large",
                stats("i-batch", 168, "0.4", "0.8", "1.5", 1_000_000L), 168L * 60, "0.096", "0.026")));

        WasteFinding f = result.findings().getFirst();
        assertThat(f.kind()).isEqualTo(WasteKind.IDLE);
        assertThat(f.monthlyHours()).as("14일 중 절반만 켜짐 → 월 365시간").isEqualByComparingTo("365");
        assertThat(f.monthlySavingsUsd()).isEqualByComparingTo(f.monthlyCostUsd()).isEqualByComparingTo("35.04");
        assertThat(f.targetSpec()).isNull();
        assertThat(f.evidence()).isEqualTo("최근 14일 CPU 1% 미만 · 사용 흔적 거의 없음");
        assertThat(f.action()).isEqualTo("쓰지 않는다면 AWS 콘솔에서 중지 검토");
    }

    @Test
    void 네트워크를_모르거나_많이_쓰면_유휴가_아니라_과다_사양으로_본다() {
        long busyNetwork = WasteDetector.IDLE_NETWORK_BYTES_PER_DAY * 14;
        Result result = WasteDetector.detect(List.of(
                candidate("i-a", "m5.large", stats("i-a", 336, "0.5", "0.9", "2", null), ALWAYS_ON, "0.118", "0.052"),
                candidate("i-b", "m5.large", stats("i-b", 336, "0.5", "0.9", "2", busyNetwork), ALWAYS_ON, "0.118",
                        "0.052")));

        assertThat(result.findings()).extracting(WasteFinding::kind).containsOnly(WasteKind.OVERSIZED);
    }

    @Test
    void 사용률이_충분하면_추천하지_않는다() {
        Result result = WasteDetector.detect(List.of(candidate("i-web", "t3.medium",
                stats("i-web", 336, "45", "70", "95", 50_000_000_000L), ALWAYS_ON, "0.052", "0.026")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        assertThat(result.judgedCount()).isEqualTo(1);
    }

    @Test
    void 판단할_수_없는_서버는_이유와_함께_빠진다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-new", "m5.large", stats("i-new", 10, "3", "4", "5", 1L), 600, "0.118", "0.052"),
                candidate("i-gpu", "g4dn.xlarge", stats("i-gpu", 336, "3", "4", "5", 10_000_000_000L), ALWAYS_ON,
                        "0.647", null),
                candidate("i-nop", "m5.large", stats("i-nop", 336, "3", "4", "5", 10_000_000_000L), ALWAYS_ON, null,
                        null),
                candidate("i-off", "m5.large", null, 0, "0.118", "0.052")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped()).extracting(WasteDetector.Skipped::instanceId)
                .as("최근 14일 동안 안 켜진 서버는 목록에도 넣지 않는다").containsExactly("i-new", "i-gpu", "i-nop");
        assertThat(result.skipped().get(0).reason()).contains("24시간 미만");
        assertThat(result.skipped().get(1).reason()).contains("GPU");
        assertThat(result.skipped().get(2).reason()).contains("단가");
    }

    @Test
    void 유휴여도_GPU_타입과_카탈로그에_없는_타입은_추천하지_않는다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-gpu", "g4dn.xlarge", stats("i-gpu", 336, "0.4", "0.8", "1.5", 1_000_000L), ALWAYS_ON,
                        "0.647", null),
                candidate("i-new", "m8g.large", stats("i-new", 336, "0.4", "0.8", "1.5", 1_000_000L), ALWAYS_ON,
                        "0.100", null)));

        assertThat(result.findings()).isEmpty();
        assertThat(result.totalMonthlySavingsUsd()).isEqualByComparingTo("0");
        assertThat(result.skipped()).extracting(WasteDetector.Skipped::instanceId).containsExactly("i-gpu", "i-new");
        assertThat(result.skipped().get(0).reason()).contains("GPU");
        assertThat(result.skipped().get(1).reason()).contains("m8g.large의 사양을 몰라");
    }

    @Test
    void 더_작은_타입이_더_싸지_않으면_추천하지_않는다() {
        Result result = WasteDetector.detect(List.of(candidate("i-a", "t3a.medium",
                stats("i-a", 336, "3", "4", "5", 10_000_000_000L), ALWAYS_ON, "0.047", "0.047")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped().getFirst().reason()).contains("더 싸지 않아");
    }

    @Test
    void 추천은_절감액_큰_순이다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-small", "t3.medium", stats("i-small", 336, "3", "4", "5", 10_000_000_000L), ALWAYS_ON,
                        "0.052", "0.026"),
                candidate("i-big", "m5.xlarge", stats("i-big", 336, "3", "4", "5", 10_000_000_000L), ALWAYS_ON,
                        "0.236", "0.104")));

        assertThat(result.findings()).extracting(f -> f.instance().instanceId()).containsExactly("i-big", "i-small");
        assertThat(result.totalMonthlySavingsUsd()).isEqualByComparingTo("115.34");
    }

    private static Candidate stoppedCandidate(String id, Instant lastUsedHour) {
        Ec2Instance stopped = new Ec2Instance(id, id + "-name", "m5.large", "ap-northeast-2", "ap-northeast-2a", "stopped",
                Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
        return new Candidate(stopped, stats(id, 200, "3", "4", "5", 10_000_000_000L), 200L * 60,
                new BigDecimal("0.118"), new BigDecimal("0.052"), lastUsedHour);
    }

    @Test
    void 지금_중지돼_있고_3일_동안_안_켜진_서버는_판단하지_않는다() {
        Instant windowEnd = Instant.parse("2026-10-08T03:00:00Z");

        Result result = WasteDetector.detect(List.of(
                stoppedCandidate("i-old", Instant.parse("2026-10-01T00:00:00Z")),
                stoppedCandidate("i-night", Instant.parse("2026-10-07T10:00:00Z"))), windowEnd);

        assertThat(result.skipped()).extracting(WasteDetector.Skipped::instanceId).containsExactly("i-old");
        assertThat(result.skipped().getFirst().reason()).contains("최근 3일 동안 켜진 적이 없어");
        assertThat(result.findings()).as("밤에 꺼 둔 서버는 지금 중지여도 판단한다")
                .extracting(f -> f.instance().instanceId()).containsExactly("i-night");
        assertThat(result.judgedCount()).isEqualTo(1);
    }
}
