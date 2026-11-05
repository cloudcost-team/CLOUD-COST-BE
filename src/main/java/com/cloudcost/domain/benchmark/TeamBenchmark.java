package com.cloudcost.domain.benchmark;

import com.cloudcost.domain.benchmark.BenchmarkReference.Percentile;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.UtilizationStats;
import com.cloudcost.domain.waste.WasteDetector;
import com.cloudcost.domain.waste.WasteFinding;
import com.cloudcost.domain.waste.WasteKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 팀 벤치마킹 계산 (이슈 #33, S09). 금액은 USD다.
 *
 * <ul>
 *   <li>과다 사양 비율 = CPU P95 &lt; 10% 서버 수 ÷ 판단한 서버 수 (최근 14일 중 24시간 이상 켜진 서버)
 *   <li>월 지출 위치 = 월말 예상이 비교 범위보다 아래·안·위
 * </ul>
 */
public final class TeamBenchmark {

    private TeamBenchmark() {
    }

    public enum Position {
        BELOW,
        WITHIN,
        ABOVE
    }

    /** 서버 1대의 판정 (S09-3) */
    public record ServerVerdict(Ec2Instance instance, BigDecimal cpuP95, int cpuHours, boolean oversized) {

        public String verdictLabel() {
            return oversized ? "과다 사양 (P95 10% 미만)" : "적정";
        }
    }

    /**
     * @param ratioPercent 과다 사양 비율 (%). 판단한 서버가 없으면 null
     */
    public record Ratio(int judgedCount, int oversizedCount, BigDecimal ratioPercent) {

        static Ratio of(int judged, int oversized) {
            BigDecimal ratio = judged == 0 ? null
                : BigDecimal.valueOf(oversized * 100L).divide(BigDecimal.valueOf(judged), 1, RoundingMode.HALF_UP);
            return new Ratio(judged, oversized, ratio);
        }
    }

    /**
     * 판단 대상과 서버별 판정. P95가 낮은 순.
     *
     * @param stats     instanceId → 최근 14일 사용률
     * @param judgedIds 낭비 탐지({@link WasteDetector})가 판단한 서버. 두 화면의 "판단한 서버"가 같도록 같은 대상을 쓴다
     *                  (24시간 이상 켜졌고, 지금 중지돼 최근 3일 동안 안 켜진 서버는 뺀다)
     */
    public static List<ServerVerdict> verdicts(
        List<Ec2Instance> instances, Map<String, UtilizationStats> stats, Set<String> judgedIds) {
        List<ServerVerdict> result = new ArrayList<>();
        for (Ec2Instance i : instances) {
            UtilizationStats s = stats.get(i.instanceId());
            if (!judgedIds.contains(i.instanceId()) || s == null || s.cpuP95() == null) {
                continue;
            }
            result.add(new ServerVerdict(i, s.cpuP95(), s.cpuHours(),
                s.cpuP95().compareTo(WasteDetector.OVERSIZED_CPU_P95) < 0));
        }
        result.sort(Comparator.comparing(ServerVerdict::cpuP95).thenComparing(v -> v.instance().instanceId()));
        return List.copyOf(result);
    }

    public static Ratio ratio(List<ServerVerdict> verdicts) {
        return Ratio.of(verdicts.size(), (int) verdicts.stream().filter(ServerVerdict::oversized).count());
    }

    /**
     * 추천을 적용한 뒤의 비율. 과다 사양 추천을 적용한 서버는 적정이 되고, 유휴 추천(중지)을 적용한 서버는 판단 대상에서 빠진다고 본다.
     */
    public static Ratio ratioAfter(List<ServerVerdict> verdicts, List<WasteFinding> applied) {
        Set<String> downsized = ids(applied, WasteKind.OVERSIZED);
        Set<String> stopped = ids(applied, WasteKind.IDLE);
        int judged = 0;
        int oversized = 0;
        for (ServerVerdict v : verdicts) {
            String id = v.instance().instanceId();
            if (stopped.contains(id)) {
                continue;
            }
            judged++;
            if (v.oversized() && !downsized.contains(id)) {
                oversized++;
            }
        }
        return Ratio.of(judged, oversized);
    }

    /**
     * 우리보다 과다 사양 비율이 확실히 낮은(효율적인) 팀의 비율 (%). 백분위 표에서 비율이 우리보다 낮은 가장 큰 백분위다
     * (표 간격 단위의 하한값, 예: 5% 단위). 서버 1~2대 팀은 비율이 0·50·100%뿐이라 같은 값이 많아, 표 사이를 직선으로 이으면
     * 과다 사양 0%인 팀에도 "더 효율적인 팀 5%"처럼 틀린 값이 나온다. 표가 없거나 우리 비율이 없으면 null.
     */
    public static BigDecimal moreEfficientTeamsPercent(BigDecimal ratioPercent, List<Percentile> table) {
        if (ratioPercent == null || table.isEmpty()) {
            return null;
        }
        BigDecimal best = BigDecimal.ZERO;
        for (Percentile p : table) {
            if (p.ratioPercent().compareTo(ratioPercent) < 0 && p.percentile().compareTo(best) > 0) {
                best = p.percentile();
            }
        }
        return best.setScale(1, RoundingMode.HALF_UP);
    }

    /** 월 지출이 범위 아래(min 미만)·안(min~max)·위(max 초과) */
    public static Position position(BigDecimal monthlyUsd, BenchmarkReference.Spend spend) {
        if (monthlyUsd.compareTo(spend.minUsd()) < 0) {
            return Position.BELOW;
        }
        return monthlyUsd.compareTo(spend.maxUsd()) > 0 ? Position.ABOVE : Position.WITHIN;
    }

    private static Set<String> ids(List<WasteFinding> applied, WasteKind kind) {
        return applied.stream().filter(f -> f.kind() == kind).map(f -> f.instance().instanceId())
            .collect(Collectors.toSet());
    }
}
