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
import com.cloudcost.domain.waste.WasteSort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 낭비 탐지 규칙 (#10 → 기능명세서 F05, #39). 14일 = 336시간 내내 켜져 있으면 월 730시간으로 본다.
 * t3 단가는 서울 리전 Linux 온디맨드 값을 쓴다.
 */
class WasteDetectorTest {

    private static final long ALWAYS_ON = 336L * 60;
    private static final long LOTS_OF_NETWORK = 10_000_000_000L;
    private static final Map<String, BigDecimal> T3 = new LinkedHashMap<>();

    static {
        T3.put("t3.nano", new BigDecimal("0.0065"));
        T3.put("t3.micro", new BigDecimal("0.013"));
        T3.put("t3.small", new BigDecimal("0.026"));
        T3.put("t3.medium", new BigDecimal("0.052"));
        T3.put("t3.large", new BigDecimal("0.104"));
        T3.put("t3.xlarge", new BigDecimal("0.208"));
        T3.put("t3.2xlarge", new BigDecimal("0.416"));
    }

    private static Ec2Instance instance(String id, String type) {
        return new Ec2Instance(id, id + "-name", type, "ap-northeast-2", "ap-northeast-2a", "running",
                Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
    }

    private static UtilizationStats stats(String id, int hours, String avg, String p95, String max, Long network) {
        return new UtilizationStats(id, hours, new BigDecimal(avg), new BigDecimal(p95), new BigDecimal(max),
                network == null ? 0 : hours, network);
    }

    private static Candidate candidate(String id, String type, UtilizationStats stats, String hourly) {
        return candidate(id, type, stats, hourly, T3);
    }

    private static Candidate candidate(
            String id, String type, UtilizationStats stats, String hourly, Map<String, BigDecimal> prices) {
        return new Candidate(instance(id, type), stats, ALWAYS_ON, hourly == null ? null : new BigDecimal(hourly),
                prices);
    }

    @Test
    void 후보는_t3_중_메모리가_절반_이상인_타입이고_기본_성능을_안다() {
        assertThat(InstanceSpecs.downsizeCandidates("m5.large")).extracting(Spec::instanceType)
                .containsExactly("t3.medium", "t3.large", "t3.xlarge", "t3.2xlarge");
        assertThat(InstanceSpecs.downsizeCandidates("t3.medium")).extracting(Spec::instanceType)
                .as("지금 타입은 빼고 메모리 2GiB 이상").containsExactly("t3.small", "t3.large", "t3.xlarge", "t3.2xlarge");
        assertThat(InstanceSpecs.downsizeCandidates("g4dn.xlarge")).as("GPU는 추천하지 않음").isEmpty();
        assertThat(InstanceSpecs.downsizeCandidates("x9.unknown")).isEmpty();
        assertThat(InstanceSpecs.baselinePercent("t3.medium")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("20"));
        assertThat(InstanceSpecs.baselinePercent("t3.large")).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("30"));
        assertThat(InstanceSpecs.baselinePercent("m5.large")).isEmpty();
        assertThat(WasteDetector.projectedP95(new BigDecimal("12"), 4, 2)).isEqualByComparingTo("24.0");
    }

    @Test
    void 절감_비율은_판단한_서버의_월_비용과_나눠_100퍼센트를_넘지_않는다() {
        // 이번 달 중간에 켠 서버도 절감액(다음 달 한 달치)과 같은 기준인 "지금처럼 한 달 쓰면 드는 비용"으로 나눈다
        Result result = WasteDetector.detect(List.of(
                candidate("i-api", "m5.large", stats("i-api", 336, "6", "9", "23", LOTS_OF_NETWORK), "0.118"),
                candidate("i-web", "t3.medium", stats("i-web", 336, "50", "70", "90", LOTS_OF_NETWORK), "0.052")));

        assertThat(result.judgedMonthlyCostUsd()).as("(0.118 + 0.052) × 730").isEqualByComparingTo("124.10");
        assertThat(result.savingsRatePercent()).as("48.18 ÷ 124.10").isEqualByComparingTo("38.8");

        Result idleOnly = WasteDetector.detect(List.of(
                candidate("i-idle", "t3.medium", stats("i-idle", 336, "0.4", "1", "1.5", 100L), "0.052")));
        assertThat(idleOnly.savingsRatePercent()).as("유휴 서버만 있으면 전부 절감").isEqualByComparingTo("100.0");
        assertThat(WasteDetector.detect(List.of()).savingsRatePercent()).as("판단한 서버가 없으면 null").isNull();
    }

    @Test
    void 과다_사양은_예상_P95가_기본_성능_안에_드는_가장_싼_타입을_추천한다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-api", "m5.large", stats("i-api", 336, "6", "9", "23", LOTS_OF_NETWORK), "0.118")));

        WasteFinding f = result.findings().getFirst();
        assertThat(f.kind()).isEqualTo(WasteKind.OVERSIZED);
        assertThat(f.targetSpec().instanceType()).isEqualTo("t3.medium");
        assertThat(f.monthlySavingsUsd()).as("(0.118 − 0.052) × 730").isEqualByComparingTo("48.18");
        assertThat(f.reason()).isEqualTo("바꿔도 P95 9%가 t3.medium 기본 성능 20% 안 · 조건에 맞는 타입 중 가장 저렴");
        assertThat(f.memoryCheckNeeded()).as("8GiB → 4GiB").isTrue();
        assertThat(f.cautions().getFirst()).contains("8GiB → 4GiB").contains("직접 확인");
        assertThat(f.evidence()).isEqualTo("최근 14일 CPU 평균 6% (P95 9%)");
    }

    @Test
    void vCPU가_줄면_예상_P95가_올라가_기본_성능을_넘는_타입은_고르지_않는다() {
        // m5.xlarge(4 vCPU): P95 12% → t3.large(2 vCPU)면 24% ≤ 30% → t3.large
        WasteFinding fits = WasteDetector.detect(List.of(candidate("i-a", "m5.xlarge",
                stats("i-a", 336, "8", "12", "30", LOTS_OF_NETWORK), "0.236"))).findings().getFirst();
        assertThat(fits.targetSpec().instanceType()).isEqualTo("t3.large");
        assertThat(fits.reason()).startsWith("바꿔도 P95 24%가 t3.large 기본 성능 30% 안");

        // P95 18% → t3.large면 36% > 30% → 같은 4 vCPU의 t3.xlarge(40%)가 가장 싸다 (메모리는 그대로)
        WasteFinding wider = WasteDetector.detect(List.of(candidate("i-b", "m5.xlarge",
                stats("i-b", 336, "8", "18", "30", LOTS_OF_NETWORK), "0.236"))).findings().getFirst();
        assertThat(wider.targetSpec().instanceType()).isEqualTo("t3.xlarge");
        assertThat(wider.memoryCheckNeeded()).isFalse();
    }

    @Test
    void 평균이_낮아도_P95가_40퍼센트_이상이면_과다_사양이_아니다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-batch", "m5.large", stats("i-batch", 336, "9", "45", "90", LOTS_OF_NETWORK), "0.118"),
                candidate("i-web", "t3.medium", stats("i-web", 336, "45", "70", "95", LOTS_OF_NETWORK), "0.052")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped()).isEmpty();
        assertThat(result.judgedCount()).isEqualTo(2);
    }

    @Test
    void 유휴는_CPU_최대_5퍼센트_미만에_네트워크가_적고_절감액은_월_비용_전부다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-idle", "c5.large", stats("i-idle", 336, "0.4", "0.9", "1.5", 100L), "0.096")));

        WasteFinding f = result.findings().getFirst();
        assertThat(f.kind()).isEqualTo(WasteKind.IDLE);
        assertThat(f.monthlySavingsUsd()).isEqualByComparingTo("70.08");
        assertThat(f.evidence()).isEqualTo("최근 14일 CPU 최대 2% · 네트워크 하루 5MB 미만");
        assertThat(f.reason()).contains("쓰지 않는 서버");
        assertThat(f.memoryCheckNeeded()).isFalse();
    }

    @Test
    void 최대가_5퍼센트_이상이거나_네트워크를_모르면_유휴가_아니라_과다_사양으로_본다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-peak", "m5.large", stats("i-peak", 336, "0.4", "1", "6", 100L), "0.118"),
                candidate("i-nonet", "m5.large", stats("i-nonet", 336, "0.4", "1", "1.5", null), "0.118")));

        assertThat(result.findings()).extracting(WasteFinding::kind).containsOnly(WasteKind.OVERSIZED);
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void 판단할_수_없는_서버는_이유와_함께_빠진다() {
        Result result = WasteDetector.detect(List.of(
                new Candidate(instance("i-new", "m5.large"), stats("i-new", 10, "3", "4", "5", 1L), 600,
                        new BigDecimal("0.118"), T3),
                candidate("i-gpu", "g4dn.xlarge", stats("i-gpu", 336, "3", "4", "5", LOTS_OF_NETWORK), "0.647"),
                candidate("i-nop", "m5.large", stats("i-nop", 336, "3", "4", "5", LOTS_OF_NETWORK), null, Map.of()),
                candidate("i-tight", "t3.medium", stats("i-tight", 336, "9", "30", "60", LOTS_OF_NETWORK), "0.052"),
                new Candidate(instance("i-off", "m5.large"), null, 0, new BigDecimal("0.118"), T3)));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped()).extracting(WasteDetector.Skipped::instanceId)
                .as("최근 14일 동안 안 켜진 서버는 목록에도 넣지 않는다").containsExactly("i-new", "i-gpu", "i-nop", "i-tight");
        assertThat(result.skipped().get(0).reason()).contains("24시간 미만");
        assertThat(result.skipped().get(1).reason()).contains("GPU");
        assertThat(WasteDetector.detect(List.of(candidate("i-gpu-idle", "g4dn.xlarge",
                stats("i-gpu-idle", 336, "0.4", "0.9", "1.5", 100L), "0.647"))).findings())
                .as("GPU는 유휴처럼 보여도 중지를 권하지 않는다").isEmpty();
        assertThat(result.skipped().get(2).reason()).contains("단가");
        assertThat(result.skipped().get(3).reason())
                .as("t3.small이면 30% > 20%, 기본 성능이 넉넉한 타입은 더 비쌈").contains("기본 성능 안에 들면서 더 싼");
    }

    @Test
    void 메모리_절반_이상인_t3가_없으면_메모리_때문이라고_알린다() {
        // r5.4xlarge는 128GiB라 절반(64GiB) 이상인 t3가 없다 (t3.2xlarge 32GiB)
        Result result = WasteDetector.detect(List.of(candidate("i-mem", "r5.4xlarge",
                stats("i-mem", 336, "3", "4", "5", LOTS_OF_NETWORK), "1.216")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.skipped().getFirst().reason())
                .isEqualTo("CPU를 적게 쓰지만 t3 중 메모리가 지금(128GiB)의 절반 이상인 타입이 없어 추천하지 않았어요.");
    }

    @Test
    void 추천은_절감액_큰_순이고_정렬과_종류_필터를_고를_수_있다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-small", "t3.medium", stats("i-small", 336, "3", "4", "8", LOTS_OF_NETWORK), "0.052"),
                candidate("i-big", "m5.xlarge", stats("i-big", 336, "5", "8", "20", LOTS_OF_NETWORK), "0.236"),
                candidate("i-idle", "c5.large", stats("i-idle", 336, "0.4", "0.9", "1.5", 100L), "0.096")));

        assertThat(result.findings()).extracting(f -> f.instance().instanceId())
                .containsExactly("i-big", "i-idle", "i-small");
        assertThat(WasteSort.COST.apply(result.findings(), null)).extracting(f -> f.instance().instanceId())
                .as("월 추정 비용 큰 순").containsExactly("i-big", "i-idle", "i-small");
        assertThat(WasteSort.UTILIZATION.apply(result.findings(), null)).extracting(f -> f.instance().instanceId())
                .as("평균 CPU 낮은 순").containsExactly("i-idle", "i-small", "i-big");
        assertThat(WasteSort.NAME.apply(result.findings(), null)).extracting(f -> f.instance().instanceId())
                .containsExactly("i-big", "i-idle", "i-small");
        assertThat(WasteSort.SAVINGS.apply(result.findings(), WasteKind.OVERSIZED))
                .extracting(f -> f.instance().instanceId()).containsExactly("i-big", "i-small");
    }

    private static Candidate stoppedCandidate(String id, Instant lastUsedHour) {
        Ec2Instance stopped = new Ec2Instance(id, id + "-name", "m5.large", "ap-northeast-2", "ap-northeast-2a", "stopped",
                Instant.parse("2026-09-01T00:00:00Z"), "Linux/UNIX", "default", false, false);
        return new Candidate(stopped, stats(id, 200, "3", "4", "5", LOTS_OF_NETWORK), 200L * 60,
                new BigDecimal("0.118"), T3, lastUsedHour);
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

    @Test
    void 추천_근거로_예상_P95와_기본_성능과_다음_후보를_남긴다() {
        // m5.xlarge(4 vCPU, $0.236) P95 12% → t3.large(2 vCPU, 30%): 12 × 4 ÷ 2 = 24% 안, $0.104 가장 쌈.
        // 다음 후보 t3.xlarge(4 vCPU, 40%) $0.208. t3.2xlarge는 지금보다 비싸서 빠진다
        WasteFinding f = WasteDetector.detect(List.of(candidate("i-x", "m5.xlarge",
                stats("i-x", 336, "6", "12", "20", LOTS_OF_NETWORK), "0.236"))).findings().get(0);

        assertThat(f.targetSpec().instanceType()).isEqualTo("t3.large");
        assertThat(f.basis().projectedCpuP95()).isEqualByComparingTo("24.0");
        assertThat(f.basis().targetBaselinePercent()).isEqualByComparingTo("30");
        assertThat(f.basis().nextSpec().instanceType()).isEqualTo("t3.xlarge");
        assertThat(f.basis().nextHourlyUsd()).isEqualByComparingTo("0.208");

        // 조건을 만족하는 타입이 하나뿐이면 다음 후보는 없다
        WasteFinding only = WasteDetector.detect(List.of(candidate("i-y", "m5.large",
                stats("i-y", 336, "6", "9", "23", LOTS_OF_NETWORK), "0.118",
                Map.of("t3.medium", new BigDecimal("0.052"))))).findings().get(0);
        assertThat(only.basis().nextSpec()).isNull();
        assertThat(only.basis().nextHourlyUsd()).isNull();
    }

    @Test
    void 예상_P95는_반올림_전_값으로_기본_성능과_비교한다() {
        // m5.large(2 vCPU) P95 20.01% → t3.medium(2 vCPU, 기본 20%)이면 20.01 > 20 이라 안 되고, 반올림하면 20.0이 되어 잘못 통과했다
        Result result = WasteDetector.detect(List.of(candidate("i-edge", "m5.large",
                stats("i-edge", 336, "8", "20.01", "30", LOTS_OF_NETWORK), "0.118")));

        assertThat(result.findings()).extracting(f -> f.targetSpec().instanceType())
                .doesNotContain("t3.medium");
    }

    @Test
    void GPU와_사양표에_없는_타입은_판단한_서버_수에도_넣지_않는다() {
        Result result = WasteDetector.detect(List.of(
                candidate("i-gpu-busy", "g4dn.xlarge", stats("i-gpu-busy", 336, "50", "70", "90", LOTS_OF_NETWORK), "0.647"),
                candidate("i-unknown", "x9.unknown", stats("i-unknown", 336, "0.4", "0.9", "1.5", 100L), "0.1"),
                candidate("i-web", "t3.medium", stats("i-web", 336, "45", "70", "95", LOTS_OF_NETWORK), "0.052")));

        assertThat(result.judgedIds()).as("벤치마킹 판단 대상").containsExactly("i-web");
        assertThat(result.findings()).as("사양을 모르는 타입은 유휴처럼 보여도 중지를 권하지 않는다").isEmpty();
        assertThat(result.skipped()).extracting(WasteDetector.Skipped::instanceId).containsExactly("i-unknown");
        assertThat(result.skipped().getFirst().reason()).contains("사양 정보");
    }
}
