package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.spike.SpikeDetector;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 급증일 탐지 (#29). web·batch 모두 $1/시간이라 사용 시간이 곧 비용이다.
 */
class SpikeDetectorTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final Map<String, InstancePrice> PRICES = Map.of(
        "i-web", InstancePrice.of("i-web", BigDecimal.ONE, false),
        "i-batch", InstancePrice.of("i-batch", BigDecimal.ONE, false));

    /** days일 동안 web 8시간·batch 2시간(하루 $10), 마지막 날만 web·batch 시간을 바꾼다 */
    private static CostReport<LocalDate> report(int days, long lastWebHours, long lastBatchHours) {
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            Map<String, Long> m = new TreeMap<>();
            boolean last = i == days - 1;
            m.put("i-web", (last ? lastWebHours : 8) * 60);
            m.put("i-batch", (last ? lastBatchHours : 2) * 60);
            daily.put(START.plusDays(i), m);
        }
        return CostEstimator.report(daily, PRICES);
    }

    @Test
    void 평소_중앙값의_1_5배_이상이면_급증이고_가장_많이_늘어난_서버가_원인이다() {
        // 평소 $10, 마지막 날 web 24시간 + batch 3시간 = $27 → 2.7배. web이 16시간 늘어 원인
        CostReport<LocalDate> daily = report(31, 24, 3);
        LocalDate last = START.plusDays(30);

        Map<LocalDate, Spike> spikes = SpikeDetector.detect(daily, START, START, last);

        assertThat(spikes).containsOnlyKeys(last);
        Spike s = spikes.get(last);
        assertThat(s.baselineUsd()).isEqualByComparingTo("10");
        assertThat(s.baselineDays()).isEqualTo(30);
        assertThat(s.ratio()).isEqualByComparingTo("2.7");
        assertThat(s.causeInstanceId()).isEqualTo("i-web");
        assertThat(s.causeUsedMinutes()).isEqualTo(24 * 60);
        assertThat(s.causeBaselineMinutes()).isEqualTo(8 * 60);
        assertThat(s.message("web-server-1")).isEqualTo("10/1 비용 급증 · 평소의 2.7배 · web-server-1 사용 시간이 늘었어요");
        assertThat(s.shortMessage(null)).isEqualTo("10/1 비용 급증 · 평소의 2.7배 (i-web)");
    }

    @Test
    void 기준_바로_아래는_급증이_아니다() {
        // $14 = 1.4배
        CostReport<LocalDate> daily = report(31, 12, 2);
        assertThat(SpikeDetector.detect(daily, START, START, START.plusDays(30))).isEmpty();
        // $15 = 정확히 1.5배는 급증
        assertThat(SpikeDetector.detect(report(31, 13, 2), START, START, START.plusDays(30))).hasSize(1);
    }

    @Test
    void 기록이_7일_미만이면_판정하지_않는다() {
        // 기록 시작 후 6일째(직전 기록 6일)는 판정하지 않고, 7일째(직전 7일)부터 판정한다
        assertThat(SpikeDetector.detect(report(7, 24, 2), START, START, START.plusDays(6))).isEmpty();
        assertThat(SpikeDetector.detect(report(8, 24, 2), START, START, START.plusDays(7))).hasSize(1);
    }

    @Test
    void 기록_시작일_앞의_0원인_날은_평소에_넣지_않는다() {
        // 앞 20일은 기록 전(0원). 기록 시작일 이후 10일만 평소로 쓴다
        CostReport<LocalDate> daily = report(31, 24, 2);
        LocalDate availableFrom = START.plusDays(20);

        Spike s = SpikeDetector.detect(daily, availableFrom, START.plusDays(30), START.plusDays(30))
            .get(START.plusDays(30));

        assertThat(s.baselineDays()).isEqualTo(10);
        assertThat(s.baselineUsd()).isEqualByComparingTo("10");
    }

    @Test
    void 처음_사용하기_전의_0원인_날은_평소에_넣지_않는다() {
        // 기록 시작일(9/1)부터 15일은 서버가 없어 0원, 9/16부터 매일 $10. 0원을 평소에 넣으면 10/1 무렵 중앙값이 반으로 떨어져 급증으로 잡힌다
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < 46; i++) {
            daily.put(START.plusDays(i), i < 15 ? new TreeMap<>() : new TreeMap<>(Map.of("i-web", 600L)));
        }
        CostReport<LocalDate> report = CostEstimator.report(daily, PRICES);
        LocalDate firstUsed = START.plusDays(15);

        assertThat(SpikeDetector.baselineFrom(START, firstUsed)).isEqualTo(firstUsed);
        assertThat(SpikeDetector.baselineFrom(START, START.minusDays(3))).as("기록 시작일보다 앞서지 않는다").isEqualTo(START);
        assertThat(SpikeDetector.baselineFrom(START, null)).isNull();
        assertThat(SpikeDetector.detect(report, SpikeDetector.baselineFrom(START, firstUsed), START, START.plusDays(45)))
            .isEmpty();
        assertThat(SpikeDetector.detect(report, START, START, START.plusDays(45)))
            .as("기록 시작일부터 재면 생기는 문제").isNotEmpty();
    }

    @Test
    void 평소가_0원이면_판정하지_않는다() {
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            daily.put(START.plusDays(i), new TreeMap<>());
        }
        daily.put(START.plusDays(30), new TreeMap<>(Map.of("i-web", 600L)));

        assertThat(SpikeDetector.detect(CostEstimator.report(daily, PRICES), START, START, START.plusDays(30))).isEmpty();
    }

    @Test
    void 짝수_개의_중앙값은_가운데_두_값의_평균이고_가장_최근_급증을_고른다() {
        // 앞 8일: $10, $10, $10, $10, $20, $20, $20, $20 → 중앙값 $15. 9일째 $30 = 2.0배
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            daily.put(START.plusDays(i), new TreeMap<>(Map.of("i-web", (i < 4 ? 10L : 20L) * 60)));
        }
        daily.put(START.plusDays(8), new TreeMap<>(Map.of("i-web", 30L * 60)));
        Map<LocalDate, Spike> spikes =
            SpikeDetector.detect(CostEstimator.report(daily, PRICES), START, START, START.plusDays(8));

        assertThat(spikes.get(START.plusDays(8)).baselineUsd()).isEqualByComparingTo("15");
        assertThat(spikes.get(START.plusDays(8)).ratio()).isEqualByComparingTo("2.0");
        assertThat(SpikeDetector.latest(spikes)).get().extracting(Spike::date).isEqualTo(START.plusDays(8));
        assertThat(SpikeDetector.latest(Map.of())).isEmpty();
    }

    @Test
    void 사용_시간이_늘어난_서버가_없으면_원인은_null이다() {
        // 단가 없는 서버는 비용에 들어가지 않으므로, 사용 시간은 같아도 단가만 바뀐 경우를 흉내 내기 위해 가격을 바꿔 계산한다
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            daily.put(START.plusDays(i), new TreeMap<>(Map.of("i-web", 600L)));
        }
        CostReport<LocalDate> base = CostEstimator.report(daily, PRICES);
        // 마지막 날만 금액을 3배로 만든 보고서
        var periods = new java.util.ArrayList<>(base.periods());
        var last = periods.getLast();
        periods.set(periods.size() - 1, new CostEstimator.PeriodCost<>(
            last.period(), last.usedMinutes(), last.estimatedCost().multiply(BigDecimal.valueOf(3)), last.instances()));
        CostReport<LocalDate> tripled = new CostReport<>(List.copyOf(periods), base.totalMinutes(),
            base.totalEstimatedCost(), 0, List.of());

        Spike s = SpikeDetector.detect(tripled, START, START.plusDays(9), START.plusDays(9)).get(START.plusDays(9));

        assertThat(s.causeInstanceId()).isNull();
        assertThat(s.message(null)).isEqualTo("9/10 비용 급증 · 평소의 3.0배");
    }

    @Test
    void 단가를_모르는_서버는_원인으로_고르지_않는다() {
        // i-web($1)은 마지막 날 8 → 20시간, 단가 모르는 i-x는 0 → 24시간. 급증 비용은 i-web 때문이다
        Map<LocalDate, Map<String, Long>> daily = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            daily.put(START.plusDays(i), new TreeMap<>(Map.of("i-web", 8L * 60)));
        }
        daily.put(START.plusDays(10), new TreeMap<>(Map.of("i-web", 20L * 60, "i-x", 24L * 60)));

        Spike s = SpikeDetector.detect(CostEstimator.report(daily, PRICES), START, START.plusDays(10), START.plusDays(10))
            .get(START.plusDays(10));

        assertThat(s.causeInstanceId()).isEqualTo("i-web");
    }
}
