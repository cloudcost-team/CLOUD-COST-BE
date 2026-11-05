package com.cloudcost.domain.waste;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceStates;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.ec2.InstanceSpecs.Spec;
import com.cloudcost.domain.usage.UtilizationStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EC2 사용률로 낭비를 찾는다 (최근 14일, 이슈 #10 → 기준은 하네스 기능명세서 F05, 이슈 #39).
 *
 * <ul>
 *   <li><b>유휴</b>: CPU 최대 5% 미만이고 네트워크가 하루 평균 5MB 미만 → 쓰지 않는다면 중지 검토. 절감액 = 월 추정 비용 전부
 *   <li><b>과다 사양</b>: CPU 평균 10% 미만이고 P95 40% 미만 (유휴 아님) → 바꾼 뒤 예상 P95(= 현재 P95 × 현재 vCPU ÷ 새 vCPU)가
 *       새 타입 기본 성능 이하인 후보({@link InstanceSpecs#downsizeCandidates}) 중 시간당 단가가 가장 싼 타입으로 변경 검토.
 *       절감액 = 월 비용 차이. 더 싸지 않으면 추천하지 않는다
 * </ul>
 * 월 추정 비용 = 최근 14일 사용 시간을 한 달(730시간)로 환산 × 온디맨드 단가. 금액은 USD다 (응답에서 환산).
 * 조언만 한다. 실제 변경은 사용자가 AWS 콘솔에서 한다.
 */
public final class WasteDetector {

    public static final BigDecimal IDLE_CPU_MAX = BigDecimal.valueOf(5);
    public static final long IDLE_NETWORK_BYTES_PER_DAY = 5L * 1024 * 1024;
    public static final BigDecimal OVERSIZED_CPU_AVG = BigDecimal.TEN;
    public static final BigDecimal OVERSIZED_CPU_P95 = BigDecimal.valueOf(40);
    /** CPU 기록이 있는 시간이 이보다 적으면 판단하지 않는다 (P95를 믿을 만한 표본 수. 켜진 시간이 길어도 CPU 기록이 빠지면 제외) */
    public static final int MIN_CPU_HOURS = 24;
    public static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(730);
    /**
     * 지금 중지돼 있고 이 기간 동안 한 번도 켜지지 않았으면 판단하지 않는다 (이미 비용이 나지 않는 서버).
     * 밤·주말에 꺼지는 서버가 조회 시각에 따라 빠지지 않도록 "지금 중지"만으로는 빼지 않는다.
     */
    public static final Duration STOPPED_FOR = Duration.ofHours(72);

    private static final BigDecimal WINDOW_HOURS = BigDecimal.valueOf(UtilizationStats.WINDOW.toHours());
    private static final int WINDOW_DAYS = (int) UtilizationStats.WINDOW.toDays();

    private WasteDetector() {
    }

    /**
     * 판단할 인스턴스 1대.
     *
     * @param stats              최근 14일 사용률. 켜진 적이 없으면 null
     * @param usedMinutes        최근 14일 동안 켜져 있던 분
     * @param hourlyUsd          지금 타입의 온디맨드 단가. 모르면 null
     * @param candidateHourlyUsd 사양 변경 후보 타입 → 단가 ({@link InstanceSpecs#downsizeCandidates}). 단가를 모르는 타입은 빠져 있다
     * @param lastUsedHour       판단 구간 안에서 마지막으로 켜져 있던 시간. 모르면 null
     */
    public record Candidate(
        Ec2Instance instance, UtilizationStats stats, long usedMinutes, BigDecimal hourlyUsd,
        Map<String, BigDecimal> candidateHourlyUsd, Instant lastUsedHour) {

        public Candidate {
            candidateHourlyUsd = candidateHourlyUsd == null ? Map.of() : Map.copyOf(candidateHourlyUsd);
        }

        public Candidate(
            Ec2Instance instance, UtilizationStats stats, long usedMinutes, BigDecimal hourlyUsd,
            Map<String, BigDecimal> candidateHourlyUsd) {
            this(instance, stats, usedMinutes, hourlyUsd, candidateHourlyUsd, null);
        }
    }

    /** 판단하지 않은 서버와 이유 */
    public record Skipped(String instanceId, String name, String reason) {
    }

    /**
     * @param judgedIds 판단한 서버 (최근 14일 24시간 이상 켜졌고, 오래 꺼 둔 서버가 아님). 벤치마킹도 같은 대상을 쓴다
     */
    public record Result(List<WasteFinding> findings, List<Skipped> skipped, Set<String> judgedIds) {

        public Result {
            judgedIds = Set.copyOf(judgedIds);
        }

        public int judgedCount() {
            return judgedIds.size();
        }

        public BigDecimal totalMonthlySavingsUsd() {
            return findings.stream().map(WasteFinding::monthlySavingsUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public long count(WasteKind kind) {
            return findings.stream().filter(f -> f.kind() == kind).count();
        }
    }

    public static Result detect(List<Candidate> candidates) {
        return detect(candidates, null);
    }

    /**
     * @param windowEnd 판단 구간 끝. 지금 중지된 서버가 {@link #STOPPED_FOR} 동안 켜지지 않았는지 볼 때 쓴다 (null이면 보지 않음)
     */
    public static Result detect(List<Candidate> candidates, Instant windowEnd) {
        List<WasteFinding> findings = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<String> judged = new LinkedHashSet<>();
        for (Candidate c : candidates) {
            Ec2Instance info = c.instance();
            if (c.stats() == null || c.stats().cpuHours() == 0) {
                continue; // 최근 14일 동안 켜진 적이 없다 (지금 비용이 나지 않음)
            }
            if (c.stats().cpuHours() < MIN_CPU_HOURS) {
                skipped.add(new Skipped(info.instanceId(), info.name(),
                    "최근 14일 동안 CPU 기록이 있는 시간이 " + MIN_CPU_HOURS + "시간 미만이라 판단하지 않았어요."));
                continue;
            }
            if (stoppedLongAgo(c, windowEnd)) {
                skipped.add(new Skipped(info.instanceId(), info.name(),
                    "지금 중지돼 있고 최근 " + STOPPED_FOR.toHours() / 24 + "일 동안 켜진 적이 없어 비용이 나지 않는 서버로 보고 판단하지 않았어요."));
                continue;
            }
            Optional<Spec> spec = InstanceSpecs.find(info.instanceType());
            if (spec.isEmpty() || spec.get().gpu()) {
                // GPU 작업은 CPU·네트워크를 거의 안 쓸 수 있어 이 지표만으로 중지·변경을 권하지 않는다.
                // 사양표에 없는 타입은 GPU인지 알 수 없어 같은 이유로 판단 대상(벤치마킹 포함)에서 뺀다.
                if (isIdle(c.stats()) || isOversized(c.stats())) {
                    skipped.add(new Skipped(info.instanceId(), info.name(), spec.isEmpty()
                        ? "CPU를 적게 쓰지만 사양 정보(GPU 여부)를 몰라 CPU·네트워크 지표만으로 중지나 사양 변경을 추천하지 않았어요."
                        : "CPU를 적게 쓰지만 GPU 타입이라 CPU·네트워크 지표만으로 중지나 사양 변경을 추천하지 않았어요."));
                }
                continue;
            }
            judged.add(info.instanceId());
            BigDecimal monthlyHours = monthlyHours(c.usedMinutes());
            if (isIdle(c.stats())) {
                if (c.hourlyUsd() == null) {
                    skipped.add(new Skipped(info.instanceId(), info.name(), "유휴로 보이지만 온디맨드 단가를 몰라 절감액을 계산하지 못했어요."));
                    continue;
                }
                BigDecimal monthly = c.hourlyUsd().multiply(monthlyHours);
                findings.add(new WasteFinding(info, WasteKind.IDLE, c.stats(), monthlyHours,
                    spec.get(), c.hourlyUsd(), monthly,
                    null, null, null, monthly, IDLE_REASON));
                continue;
            }
            if (isOversized(c.stats())) {
                oversized(c, monthlyHours).ifPresentOrElse(findings::add, () -> skipped.add(new Skipped(
                    info.instanceId(), info.name(), oversizedSkipReason(c))));
            }
        }
        findings.sort(Comparator.comparing(WasteFinding::monthlySavingsUsd).reversed()
            .thenComparing(f -> f.instance().instanceId()));
        return new Result(List.copyOf(findings), List.copyOf(skipped), judged);
    }

    static final String IDLE_REASON = "최근 14일 CPU 최대 5% 미만이고 네트워크도 하루 5MB 미만이라 쓰지 않는 서버로 보여요.";

    private static boolean stoppedLongAgo(Candidate c, Instant windowEnd) {
        return windowEnd != null && InstanceStates.isStopped(c.instance().state()) && c.lastUsedHour() != null
            && c.lastUsedHour().isBefore(windowEnd.minus(STOPPED_FOR));
    }

    /** 유휴: CPU 최대 5% 미만 + 네트워크를 알고 하루 평균 5MB 미만. 네트워크를 모르면 유휴로 보지 않는다. */
    static boolean isIdle(UtilizationStats stats) {
        return stats.cpuMax() != null && stats.cpuMax().compareTo(IDLE_CPU_MAX) < 0
            && stats.networkBytes() != null && stats.networkBytes() < IDLE_NETWORK_BYTES_PER_DAY * WINDOW_DAYS;
    }

    /** 과다 사양: CPU 평균 10% 미만 그리고 P95 40% 미만 */
    static boolean isOversized(UtilizationStats stats) {
        return stats.cpuAvg() != null && stats.cpuAvg().compareTo(OVERSIZED_CPU_AVG) < 0
            && stats.cpuP95() != null && stats.cpuP95().compareTo(OVERSIZED_CPU_P95) < 0;
    }

    /** 바꾼 뒤 예상 P95 = 현재 P95 × 현재 vCPU ÷ 새 vCPU (소수 첫째 자리) */
    public static BigDecimal projectedP95(BigDecimal p95, int currentVcpu, int targetVcpu) {
        return p95.multiply(BigDecimal.valueOf(currentVcpu))
            .divide(BigDecimal.valueOf(targetVcpu), 1, RoundingMode.HALF_UP);
    }

    /** 조건을 만족하는 후보와 근거 */
    private record Choice(Spec target, BigDecimal hourlyUsd, BigDecimal projectedP95, BigDecimal baselinePercent) {
    }

    /**
     * 예상 P95가 기본 성능 이하이고 지금보다 싼 후보를 싼 순서로 (단가가 같으면 메모리가 큰 것 먼저).
     * 첫째가 추천, 둘째가 S07-2의 "다음 후보"다.
     */
    private static List<Choice> qualifying(Candidate c, Spec current) {
        List<Choice> result = new ArrayList<>();
        for (Spec t : InstanceSpecs.downsizeCandidates(current.instanceType())) {
            BigDecimal price = c.candidateHourlyUsd().get(t.instanceType());
            Optional<BigDecimal> baseline = InstanceSpecs.baselinePercent(t.instanceType());
            if (price == null || baseline.isEmpty() || price.compareTo(c.hourlyUsd()) >= 0) {
                continue;
            }
            // 반올림 전 값으로 비교한다: P95 × 현재 vCPU ≤ 기본 성능 × 새 vCPU (표시용 예상 P95는 반올림)
            if (c.stats().cpuP95().multiply(BigDecimal.valueOf(current.vcpu()))
                .compareTo(baseline.get().multiply(BigDecimal.valueOf(t.vcpu()))) > 0) {
                continue;
            }
            result.add(new Choice(t, price, projectedP95(c.stats().cpuP95(), current.vcpu(), t.vcpu()), baseline.get()));
        }
        result.sort(Comparator.comparing(Choice::hourlyUsd)
            .thenComparing(ch -> ch.target().memoryGib(), Comparator.reverseOrder()));
        return result;
    }

    private static Optional<WasteFinding> oversized(Candidate c, BigDecimal monthlyHours) {
        Optional<Spec> current = InstanceSpecs.find(c.instance().instanceType()).filter(s -> !s.gpu());
        if (current.isEmpty() || c.hourlyUsd() == null) {
            return Optional.empty();
        }
        List<Choice> choices = qualifying(c, current.get());
        if (choices.isEmpty()) {
            return Optional.empty();
        }
        Choice ch = choices.get(0);
        Choice next = choices.size() > 1 ? choices.get(1) : null;
        BigDecimal monthly = c.hourlyUsd().multiply(monthlyHours);
        BigDecimal targetMonthly = ch.hourlyUsd().multiply(monthlyHours);
        String projected = WasteFinding.percent(ch.projectedP95());
        String reason = "바꿔도 P95 " + projected + (projected.endsWith("미만") ? "이 " : "가 ") + ch.target().instanceType()
            + " 기본 성능 " + ch.baselinePercent().stripTrailingZeros().toPlainString() + "% 안 · 조건에 맞는 타입 중 가장 저렴";
        return Optional.of(new WasteFinding(c.instance(), WasteKind.OVERSIZED, c.stats(), monthlyHours, current.get(),
            c.hourlyUsd(), monthly, ch.target(), ch.hourlyUsd(), targetMonthly, monthly.subtract(targetMonthly), reason,
            new WasteFinding.DownsizeBasis(ch.projectedP95(), ch.baselinePercent(),
                next == null ? null : next.target(), next == null ? null : next.hourlyUsd())));
    }

    private static String oversizedSkipReason(Candidate c) {
        String type = c.instance().instanceType();
        if (InstanceSpecs.find(type).filter(InstanceSpecs.Spec::gpu).isPresent()) {
            return "CPU를 적게 쓰지만 GPU 타입이라 CPU 사용률만으로 사양 변경을 추천하지 않았어요.";
        }
        if (InstanceSpecs.downsizeCandidates(type).isEmpty()) {
            // 사양을 모르는 타입은 판단 전에 빠지므로, 여기는 메모리를 절반 아래로 줄이지 않는 t3 후보가 없는 경우다 (예: r5.4xlarge)
            BigDecimal memory = InstanceSpecs.find(type).map(Spec::memoryGib).orElse(null);
            return "CPU를 적게 쓰지만 " + InstanceSpecs.TARGET_FAMILY + " 중 메모리가 지금"
                + (memory == null ? "" : "(" + memory.stripTrailingZeros().toPlainString() + "GiB)")
                + "의 절반 이상인 타입이 없어 추천하지 않았어요.";
        }
        if (c.hourlyUsd() == null || c.candidateHourlyUsd().isEmpty()) {
            return "CPU를 적게 쓰지만 온디맨드 단가를 몰라 절감액을 계산하지 못했어요.";
        }
        return "CPU를 적게 쓰지만 바꾼 뒤에도 기본 성능 안에 들면서 더 싼 t3 타입이 없어 추천하지 않았어요.";
    }

    /** 최근 14일 사용 시간을 한 달(730시간)로 환산. 730시간을 넘지 않는다. */
    static BigDecimal monthlyHours(long usedMinutes) {
        BigDecimal hours = BigDecimal.valueOf(usedMinutes).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        BigDecimal monthly = hours.multiply(HOURS_PER_MONTH).divide(WINDOW_HOURS, 2, RoundingMode.HALF_UP);
        return monthly.min(HOURS_PER_MONTH.setScale(2));
    }
}
