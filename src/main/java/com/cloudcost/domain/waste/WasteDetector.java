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
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * EC2 사용률로 낭비를 찾는다 (최근 14일, 이슈 #10).
 *
 * <ul>
 *   <li><b>유휴</b>: CPU 평균 1% 미만이고 네트워크가 하루 평균 5MB 미만 → 쓰지 않는다면 중지 검토. 절감액 = 월 추정 비용 전부
 *   <li><b>과다 사양</b>: CPU P95 10% 미만 (유휴 아님) → {@link InstanceSpecs#downsizeTarget} 타입으로 변경 검토.
 *       절감액 = 월 비용 차이. 더 싸지 않으면 추천하지 않는다
 * </ul>
 * 월 추정 비용 = 최근 14일 사용 시간을 한 달(730시간)로 환산 × 온디맨드 단가. 금액은 USD다 (응답에서 환산).
 * 조언만 한다. 실제 변경은 사용자가 AWS 콘솔에서 한다.
 */
public final class WasteDetector {

    public static final BigDecimal IDLE_CPU_AVG = BigDecimal.ONE;
    public static final long IDLE_NETWORK_BYTES_PER_DAY = 5L * 1024 * 1024;
    public static final BigDecimal OVERSIZED_CPU_P95 = BigDecimal.TEN;
    /** 이보다 적게 켜져 있었으면 판단하지 않는다 */
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
     * @param stats           최근 14일 사용률. 켜진 적이 없으면 null
     * @param usedMinutes     최근 14일 동안 켜져 있던 분
     * @param hourlyUsd       지금 타입의 온디맨드 단가. 모르면 null
     * @param targetHourlyUsd 줄인 타입({@link InstanceSpecs#downsizeTarget})의 단가. 후보가 없거나 모르면 null
     * @param lastUsedHour    판단 구간 안에서 마지막으로 켜져 있던 시간. 모르면 null
     */
    public record Candidate(
        Ec2Instance instance, UtilizationStats stats, long usedMinutes, BigDecimal hourlyUsd,
        BigDecimal targetHourlyUsd, Instant lastUsedHour) {

        public Candidate(
            Ec2Instance instance, UtilizationStats stats, long usedMinutes, BigDecimal hourlyUsd,
            BigDecimal targetHourlyUsd) {
            this(instance, stats, usedMinutes, hourlyUsd, targetHourlyUsd, null);
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
                    "최근 14일 동안 켜져 있던 시간이 " + MIN_CPU_HOURS + "시간 미만이라 판단하지 않았어요."));
                continue;
            }
            if (stoppedLongAgo(c, windowEnd)) {
                skipped.add(new Skipped(info.instanceId(), info.name(),
                    "지금 중지돼 있고 최근 " + STOPPED_FOR.toHours() / 24 + "일 동안 켜진 적이 없어 비용이 나지 않는 서버로 보고 판단하지 않았어요."));
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
                    InstanceSpecs.find(info.instanceType()).orElse(null), c.hourlyUsd(), monthly,
                    null, null, null, monthly));
                continue;
            }
            if (c.stats().cpuP95() != null && c.stats().cpuP95().compareTo(OVERSIZED_CPU_P95) < 0) {
                oversized(c, monthlyHours).ifPresentOrElse(findings::add, () -> skipped.add(new Skipped(
                    info.instanceId(), info.name(), oversizedSkipReason(c))));
            }
        }
        findings.sort(Comparator.comparing(WasteFinding::monthlySavingsUsd).reversed()
            .thenComparing(f -> f.instance().instanceId()));
        return new Result(List.copyOf(findings), List.copyOf(skipped), judged);
    }

    private static boolean stoppedLongAgo(Candidate c, Instant windowEnd) {
        return windowEnd != null && InstanceStates.isStopped(c.instance().state()) && c.lastUsedHour() != null
            && c.lastUsedHour().isBefore(windowEnd.minus(STOPPED_FOR));
    }

    /** 유휴: CPU 평균 1% 미만 + 네트워크를 알고 하루 평균 5MB 미만. 네트워크를 모르면 유휴로 보지 않는다. */
    static boolean isIdle(UtilizationStats stats) {
        return stats.cpuAvg() != null && stats.cpuAvg().compareTo(IDLE_CPU_AVG) < 0
            && stats.networkBytes() != null && stats.networkBytes() < IDLE_NETWORK_BYTES_PER_DAY * WINDOW_DAYS;
    }

    private static Optional<WasteFinding> oversized(Candidate c, BigDecimal monthlyHours) {
        Optional<Spec> current = InstanceSpecs.find(c.instance().instanceType());
        Optional<Spec> target = InstanceSpecs.downsizeTarget(c.instance().instanceType());
        if (current.isEmpty() || target.isEmpty() || c.hourlyUsd() == null || c.targetHourlyUsd() == null
            || c.targetHourlyUsd().compareTo(c.hourlyUsd()) >= 0) {
            return Optional.empty();
        }
        BigDecimal monthly = c.hourlyUsd().multiply(monthlyHours);
        BigDecimal targetMonthly = c.targetHourlyUsd().multiply(monthlyHours);
        return Optional.of(new WasteFinding(c.instance(), WasteKind.OVERSIZED, c.stats(), monthlyHours, current.get(),
            c.hourlyUsd(), monthly, target.get(), c.targetHourlyUsd(), targetMonthly, monthly.subtract(targetMonthly)));
    }

    private static String oversizedSkipReason(Candidate c) {
        String type = c.instance().instanceType();
        if (InstanceSpecs.find(type).filter(InstanceSpecs.Spec::gpu).isPresent()) {
            return "CPU를 적게 쓰지만 GPU 타입이라 CPU 사용률만으로 사양 변경을 추천하지 않았어요.";
        }
        if (InstanceSpecs.downsizeTarget(type).isEmpty()) {
            return "CPU를 적게 쓰지만 " + type + "보다 한 단계 작은 타입을 정하지 못해 추천하지 않았어요.";
        }
        if (c.hourlyUsd() == null || c.targetHourlyUsd() == null) {
            return "CPU를 적게 쓰지만 온디맨드 단가를 몰라 절감액을 계산하지 못했어요.";
        }
        return "CPU를 적게 쓰지만 더 작은 타입(" + InstanceSpecs.downsizeTarget(type).get().instanceType()
            + ")이 더 싸지 않아 추천하지 않았어요.";
    }

    /** 최근 14일 사용 시간을 한 달(730시간)로 환산. 730시간을 넘지 않는다. */
    static BigDecimal monthlyHours(long usedMinutes) {
        BigDecimal hours = BigDecimal.valueOf(usedMinutes).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        BigDecimal monthly = hours.multiply(HOURS_PER_MONTH).divide(WINDOW_HOURS, 2, RoundingMode.HALF_UP);
        return monthly.min(HOURS_PER_MONTH.setScale(2));
    }
}
