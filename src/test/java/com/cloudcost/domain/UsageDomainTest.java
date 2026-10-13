package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.CollectionWindows;
import com.cloudcost.domain.usage.CollectionWindows.TimeWindow;
import com.cloudcost.domain.usage.CurrentRun;
import com.cloudcost.domain.usage.DateRange;
import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.RefreshPolicy;
import com.cloudcost.domain.usage.UsageAggregator;
import com.cloudcost.domain.usage.UsageCalculator;
import com.cloudcost.fakes.Fixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UsageDomainTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal FULL = BigDecimal.valueOf(60);

    private static HourlySample sample(String id, String hourUtc, double count) {
        return new HourlySample(id, Instant.parse(hourUtc), BigDecimal.valueOf(count));
    }

    @Nested
    class 사용_시간_계산 {

        @Test
        void 꽉_찬_한_시간은_SampleCount_60이고_60분() {
            assertThat(UsageCalculator.usedMinutes(FULL)).isEqualTo(60);
        }

        @Test
        void 일부만_켜진_시간은_SampleCount가_곧_사용_분() {
            // 실제 계정 QA: 05:00~05:12 실행 → SampleCount 13
            assertThat(UsageCalculator.usedMinutes(BigDecimal.valueOf(13))).isEqualTo(13);
            assertThat(UsageCalculator.usedMinutes(BigDecimal.valueOf(59))).isEqualTo(59);
        }

        @Test
        void 데이터_포인트가_없으면_0분() {
            assertThat(UsageCalculator.usedMinutes(BigDecimal.ZERO)).isZero();
            assertThat(UsageCalculator.usedMinutes(null)).isZero();
        }

        @Test
        void 오차로_60을_넘으면_60분으로_자른다() {
            assertThat(UsageCalculator.usedMinutes(BigDecimal.valueOf(61))).isEqualTo(60);
        }
    }

    @Nested
    class 일별_월별_집계 {

        @Test
        void KST_날짜_경계로_일별_사용_분을_나누고_빈_날짜도_포함한다() {
            // 2026-10-06T14:00Z = KST 10/06 23:00, 15:00Z = KST 10/07 00:00
            List<HourlySample> samples = List.of(
                    sample("i-a", "2026-10-06T14:00:00Z", 60),
                    sample("i-a", "2026-10-06T15:00:00Z", 60),
                    sample("i-a", "2026-10-06T16:00:00Z", 30),
                    sample("i-b", "2026-10-06T15:00:00Z", 60));
            Map<LocalDate, Map<String, Long>> daily = UsageAggregator.dailyMinutes(
                    samples, KST,
                    LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7));

            assertThat(daily).containsOnlyKeys(
                    LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7));
            assertThat(daily.get(LocalDate.of(2026, 10, 5))).isEmpty();
            assertThat(daily.get(LocalDate.of(2026, 10, 6))).containsExactly(Map.entry("i-a", 60L));
            assertThat(daily.get(LocalDate.of(2026, 10, 7))).containsEntry("i-a", 90L).containsEntry("i-b", 60L);
        }

        @Test
        void 기간_밖의_데이터는_무시한다() {
            List<HourlySample> samples = List.of(sample("i-a", "2026-09-01T00:00:00Z", 60));
            var daily = UsageAggregator.dailyMinutes(samples, KST, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));
            assertThat(daily.get(LocalDate.of(2026, 10, 1))).isEmpty();
        }

        @Test
        void 월별은_일별_합산이다() {
            List<HourlySample> samples = new ArrayList<>();
            samples.add(sample("i-a", "2026-09-30T00:00:00Z", 60)); // KST 9/30
            samples.add(sample("i-a", "2026-09-30T15:00:00Z", 60)); // KST 10/01 00:00
            samples.add(sample("i-a", "2026-10-02T00:00:00Z", 60));
            var daily = UsageAggregator.dailyMinutes(samples, KST,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));
            Map<YearMonth, Map<String, Long>> monthly = UsageAggregator.monthlyMinutes(daily);

            assertThat(monthly).containsOnlyKeys(YearMonth.of(2026, 9), YearMonth.of(2026, 10));
            assertThat(monthly.get(YearMonth.of(2026, 9))).containsEntry("i-a", 60L);
            assertThat(monthly.get(YearMonth.of(2026, 10))).containsEntry("i-a", 120L);
            assertThat(UsageAggregator.totalMinutes(monthly)).containsEntry("i-a", 180L);
        }
    }

    @Nested
    class CloudWatch_조회_구간 {

        private final Instant now = Instant.parse("2026-10-08T03:25:00Z");

        @Test
        void 처음에는_소급_기간을_30일_단위로_나눈다() {
            List<TimeWindow> windows = CollectionWindows.plan(null, now, Duration.ofDays(90), Duration.ofHours(2));
            assertThat(windows).as("90일 = 30일 × 3번").hasSize(3);
            assertThat(windows.getFirst().start()).isEqualTo(Instant.parse("2026-07-10T04:00:00Z"));
            assertThat(windows.getLast().end()).isEqualTo(now);
            windows.forEach(w -> assertThat(Duration.between(w.start(), w.end())).isLessThanOrEqualTo(Duration.ofDays(30)));
        }

        @Test
        void 이후에는_마지막_수집_직전_시간부터_한_번만_조회한다() {
            Instant last = Instant.parse("2026-10-08T02:10:00Z");
            List<TimeWindow> windows = CollectionWindows.plan(last, now, Duration.ofDays(90), Duration.ofHours(2));
            assertThat(windows).containsExactly(new TimeWindow(Instant.parse("2026-10-08T00:00:00Z"), now));
        }

        @Test
        void 최대_455일보다_이전은_요청하지_않는다() {
            List<TimeWindow> windows = CollectionWindows.plan(null, now, Duration.ofDays(1000), Duration.ofHours(2));
            assertThat(windows.getFirst().start()).isAfterOrEqualTo(now.minus(Duration.ofDays(455)));
            assertThat(windows).hasSize(16); // 455일 = 30일 × 15 + 5일
        }
    }

    @Nested
    class 캐시_정책 {

        private final RefreshPolicy policy = new RefreshPolicy(Duration.ofMinutes(10), Duration.ofHours(1));
        private final Instant last = Instant.parse("2026-10-08T00:00:00Z");

        @Test
        void CloudWatch는_1시간_안에는_다시_부르지_않는다() {
            assertThat(policy.usageStale(null, last)).isTrue();
            assertThat(policy.usageStale(last, last.plus(Duration.ofMinutes(59)))).isFalse();
            assertThat(policy.usageStale(last, last.plus(Duration.ofHours(1)))).isTrue();
            assertThat(policy.nextUsageRefreshAt(last)).isEqualTo(last.plus(Duration.ofHours(1)));
        }

        @Test
        void 인스턴스_정보는_10분마다_갱신한다() {
            assertThat(policy.instancesStale(last, last.plus(Duration.ofMinutes(9)))).isFalse();
            assertThat(policy.instancesStale(last, last.plus(Duration.ofMinutes(10)))).isTrue();
        }
    }

    @Nested
    class 인스턴스_상태 {

        private final Instant now = Instant.parse("2026-10-08T03:00:00Z");

        @Test
        void 실행_중이면_LaunchTime부터_현재_실행_구간() {
            Ec2Instance running = Fixtures.instance("i-a", "t3.micro", "running", Instant.parse("2026-10-07T03:00:00Z"));
            assertThat(CurrentRun.of(running, now)).hasValueSatisfying(run -> {
                assertThat(run.since()).isEqualTo(Instant.parse("2026-10-07T03:00:00Z"));
                assertThat(run.hours()).isEqualByComparingTo("24.00");
            });
            Ec2Instance stopped = Fixtures.instance("i-b", "t3.micro", "stopped", Instant.parse("2026-10-07T03:00:00Z"));
            assertThat(CurrentRun.of(stopped, now)).isEmpty();
        }

        @Test
        void 계속_꺼져_있던_인스턴스는_CloudWatch를_다시_부르지_않는다() {
            Ec2Instance stopped = Fixtures.instance("i-a", "t3.micro", "stopped", null);
            TrackedInstance first = TrackedInstance.firstSeen(UUID.randomUUID(), "o", UUID.randomUUID(), stopped, now);
            assertThat(first.needsUsageCollection()).as("처음 보는 인스턴스는 소급 수집").isTrue();

            TrackedInstance collected = first.usageCollected(now);
            assertThat(collected.needsUsageCollection()).isFalse();

            Ec2Instance running = Fixtures.instance("i-a", "t3.micro", "running", now);
            TrackedInstance startedThenStopped = collected.observed(running, now).observed(stopped, now);
            assertThat(startedThenStopped.needsUsageCollection()).as("수집 사이에 켜진 적이 있으면 다시 수집").isTrue();
        }
    }

    @Test
    void 조회_기간은_최대_455일이고_from이_to보다_늦을_수_없다() {
        assertThat(DateRange.resolve(null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)))
                .isEqualTo(new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)));
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 1)))
                .hasMessageContaining("455");
    }
}
