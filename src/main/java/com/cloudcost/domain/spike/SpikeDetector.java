package com.cloudcost.domain.spike;

import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.CostEstimator.InstanceCost;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 비용 급증일 탐지 (이슈 #29, S06 배너·빨간 막대).
 *
 * <ul>
 *   <li><b>평소</b>: 그날 직전 30일 중 기록이 있는 날(기록 시작일 이후, 처음 사용한 날부터)의 하루 추정 비용 중앙값
 *   <li><b>급증</b>: 그날 비용 ≥ 평소 × 1.5. 평소를 잰 날이 7일 미만이거나 평소가 0이면 판정하지 않는다
 *   <li><b>원인 서버</b>: 그날 사용 시간이 자기 평소(같은 30일의 하루 사용 시간 중앙값)보다 가장 많이 늘어난 인스턴스
 * </ul>
 * 금액은 USD다. 단가를 모르는 서버는 비용에 들어가지 않는다 (기존 합계와 같은 규칙).
 */
public final class SpikeDetector {

    public static final int BASELINE_DAYS = 30;
    public static final int MIN_BASELINE_DAYS = 7;
    public static final BigDecimal THRESHOLD = new BigDecimal("1.5");

    private SpikeDetector() {
    }

    /**
     * 급증일 1건.
     *
     * @param baselineUsd           평소 하루 비용 (직전 30일 중앙값)
     * @param baselineDays          평소를 잰 날 수 (7~30)
     * @param ratio                 그날 비용 ÷ 평소 (소수 첫째 자리)
     * @param causeInstanceId       원인 서버. 사용 시간이 늘어난 서버가 없으면 null
     * @param causeUsedMinutes      원인 서버의 그날 사용 분
     * @param causeBaselineMinutes  원인 서버의 평소 하루 사용 분 (중앙값)
     */
    public record Spike(
        LocalDate date,
        BigDecimal costUsd,
        BigDecimal baselineUsd,
        int baselineDays,
        BigDecimal ratio,
        String causeInstanceId,
        long causeUsedMinutes,
        long causeBaselineMinutes) {

        /** 예: "10/12 비용 급증 · 평소의 2.4배 · web-server-1 사용 시간이 늘었어요" */
        public String message(String causeName) {
            String head = date.getMonthValue() + "/" + date.getDayOfMonth() + " 비용 급증 · 평소의 "
                + ratio.toPlainString() + "배";
            if (causeInstanceId == null) {
                return head;
            }
            return head + " · " + displayName(causeName) + " 사용 시간이 늘었어요";
        }

        /** 알림용 짧은 문구. 예: "10/12 비용 급증 · 평소의 2.4배 (web-server-1)" */
        public String shortMessage(String causeName) {
            String head = date.getMonthValue() + "/" + date.getDayOfMonth() + " 비용 급증 · 평소의 "
                + ratio.toPlainString() + "배";
            return causeInstanceId == null ? head : head + " (" + displayName(causeName) + ")";
        }

        private String displayName(String causeName) {
            return causeName == null || causeName.isBlank() ? causeInstanceId : causeName;
        }
    }

    /**
     * @param daily         일별 비용 (날짜 → 비용·서버별 사용 분). 평가 기간 앞 30일도 들어 있어야 평소를 잴 수 있다
     * @param baselineFrom  평소를 재기 시작하는 날 = 기록 시작일과 처음 사용한 날 중 늦은 날 ({@link #baselineFrom}).
     *                      null이면 기록이 없다 (판정하지 않음). 조회 기간과 상관없이 같은 값을 넘겨야 같은 날의 판정이 같다
     * @param from          판정할 첫날 (포함)
     * @param to            판정할 마지막 날 (포함)
     * @return 날짜 → 급증. 급증이 아닌 날·판정하지 않은 날은 들어가지 않는다
     */
    public static Map<LocalDate, Spike> detect(
        CostReport<LocalDate> daily, LocalDate baselineFrom, LocalDate from, LocalDate to) {
        Map<LocalDate, Spike> result = new LinkedHashMap<>();
        if (baselineFrom == null) {
            return result;
        }
        Map<LocalDate, PeriodCost<LocalDate>> byDay = new HashMap<>();
        daily.periods().forEach(p -> byDay.put(p.period(), p));
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            evaluate(byDay, baselineFrom, d).ifPresent(s -> result.put(s.date(), s));
        }
        return result;
    }

    /**
     * 평소를 재기 시작하는 날. 기록 시작일 뒤라도 처음 쓰기 전의 0원인 날은 "기록"이 아니다
     * (넣으면 서버를 켠 지 보름쯤 된 날 30일 창에 0원과 쓴 날이 반씩 섞여 평소대로 쓴 날이 급증으로 잡힌다).
     * 처음 사용한 뒤 꺼 둔 날의 0원은 평소에 넣는다.
     *
     * @param firstUsedDay 전체 기록에서 처음 켜져 있던 날 (조회 기간 안이 아니라). 없으면 null
     */
    public static LocalDate baselineFrom(LocalDate availableFrom, LocalDate firstUsedDay) {
        if (availableFrom == null || firstUsedDay == null) {
            return null;
        }
        return firstUsedDay.isAfter(availableFrom) ? firstUsedDay : availableFrom;
    }

    /** 가장 최근 급증 (없으면 empty) */
    public static Optional<Spike> latest(Map<LocalDate, Spike> spikes) {
        return spikes.values().stream().max((a, b) -> a.date().compareTo(b.date()));
    }

    static Optional<Spike> evaluate(Map<LocalDate, PeriodCost<LocalDate>> byDay, LocalDate availableFrom, LocalDate day) {
        PeriodCost<LocalDate> today = byDay.get(day);
        if (today == null || day.isBefore(availableFrom)) {
            return Optional.empty();
        }
        List<PeriodCost<LocalDate>> basis = new ArrayList<>();
        for (LocalDate d = day.minusDays(BASELINE_DAYS); d.isBefore(day); d = d.plusDays(1)) {
            PeriodCost<LocalDate> p = byDay.get(d);
            if (p != null && !d.isBefore(availableFrom)) {
                basis.add(p);
            }
        }
        if (basis.size() < MIN_BASELINE_DAYS) {
            return Optional.empty();
        }
        BigDecimal baseline = median(basis.stream().map(SpikeDetector::cost).toList());
        BigDecimal cost = cost(today);
        if (baseline.signum() <= 0 || cost.compareTo(baseline.multiply(THRESHOLD)) < 0) {
            return Optional.empty();
        }
        BigDecimal ratio = cost.divide(baseline, 1, RoundingMode.HALF_UP);

        Map<String, Long> usedToday = minutes(today);
        Set<String> ids = new TreeSet<>(usedToday.keySet());
        String cause = null;
        long causeIncrease = 0;
        long causeBaseline = 0;
        for (String id : ids) {
            long usual = medianMinutes(basis.stream().map(p -> minutes(p).getOrDefault(id, 0L)).toList());
            long increase = usedToday.get(id) - usual;
            if (increase > causeIncrease) {
                cause = id;
                causeIncrease = increase;
                causeBaseline = usual;
            }
        }
        return Optional.of(new Spike(day, cost, baseline.setScale(6, RoundingMode.HALF_UP), basis.size(), ratio, cause,
            cause == null ? 0 : usedToday.get(cause), causeBaseline));
    }

    private static BigDecimal cost(PeriodCost<LocalDate> p) {
        return p.estimatedCost() == null ? BigDecimal.ZERO : p.estimatedCost();
    }

    private static Map<String, Long> minutes(PeriodCost<LocalDate> p) {
        Map<String, Long> result = new HashMap<>();
        for (InstanceCost c : p.instances()) {
            result.merge(c.instanceId(), c.usedMinutes(), Long::sum);
        }
        return result;
    }

    /** 짝수 개면 가운데 두 값의 평균 */
    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    private static long medianMinutes(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
