package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.demo.DemoCatalog.Os;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.demo.DemoSchedule;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.demo.DemoSetup.Server;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.UsageAggregator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 체험 모드: 사용자가 정한 서버 구성 → 인스턴스·시간별 샘플. 기준 시각은 2026-10-08(목) 12:00 KST.
 */
class DemoFleetTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    private static Server server(String name, int count, DemoSchedule schedule, int hoursPerDay) {
        return new Server(name, "t3.medium", Os.LINUX, count, schedule, hoursPerDay);
    }

    private static DemoFleet fleet(Server... servers) {
        return new DemoFleet(new DemoSetup(List.of(servers), OCT_1, LocalDate.of(2026, 10, 8)), NOW, KST);
    }

    private static Map<String, Ec2Instance> byName(DemoFleet fleet) {
        return fleet.instances().stream().collect(Collectors.toMap(Ec2Instance::name, Function.identity()));
    }

    private static Map<String, Long> minutesOn(DemoFleet fleet, LocalDate day) {
        var samples = fleet.hourlySamples(day.atStartOfDay(KST).toInstant(), day.plusDays(1).atStartOfDay(KST).toInstant());
        return UsageAggregator.dailyMinutes(samples, KST, day, day).get(day);
    }

    @Test
    void 대수만큼_인스턴스를_펼치고_실제_형식의_ID를_준다() {
        DemoFleet fleet = fleet(server("web", 2, DemoSchedule.ALWAYS, 0), server("db", 1, DemoSchedule.ALWAYS, 0));

        assertThat(fleet.instances()).extracting(Ec2Instance::name).containsExactly("web-1", "web-2", "db");
        assertThat(fleet.instances()).allSatisfy(i -> {
            assertThat(i.instanceId()).matches("i-0d3e[0-9a-f]{13}");
            assertThat(i.region()).isEqualTo("ap-northeast-2");
        });
        assertThat(fleet.instances()).extracting(Ec2Instance::instanceId).doesNotHaveDuplicates();
        assertThat(fleet.prices()).allMatch(p -> p.priced());
    }

    @Test
    void 상태와_LaunchTime은_사용_패턴과_지금_시각에서_나온다() {
        Map<String, Ec2Instance> d = byName(fleet(
            server("always", 1, DemoSchedule.ALWAYS, 0),
            server("office", 1, DemoSchedule.WEEKDAY_OFFICE, 0),
            server("two-hours", 1, DemoSchedule.DAILY_HOURS, 2)));

        assertThat(d.get("always").state()).isEqualTo("running");
        assertThat(d.get("always").launchTime()).as("기간 시작일 0시").isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(d.get("office").state()).as("평일 12시는 업무 시간").isEqualTo("running");
        assertThat(d.get("office").launchTime()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(d.get("two-hours").state()).as("09~11시만 켬").isEqualTo("stopped");
        assertThat(d.get("two-hours").launchTime()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
    }

    @Test
    void 하루_사용_시간은_패턴대로_쌓인다() {
        DemoFleet fleet = fleet(
            server("always", 1, DemoSchedule.ALWAYS, 0),
            server("office", 1, DemoSchedule.WEEKDAY_OFFICE, 0),
            server("six-hours", 1, DemoSchedule.DAILY_HOURS, 6));
        Map<String, String> ids = byName(fleet).values().stream()
            .collect(Collectors.toMap(Ec2Instance::name, Ec2Instance::instanceId));

        Map<String, Long> wednesday = minutesOn(fleet, LocalDate.of(2026, 10, 7));
        assertThat(wednesday.get(ids.get("always"))).isEqualTo(24 * 60);
        assertThat(wednesday.get(ids.get("office"))).isEqualTo(10 * 60);
        assertThat(wednesday.get(ids.get("six-hours"))).isEqualTo(6 * 60);

        Map<String, Long> sunday = minutesOn(fleet, LocalDate.of(2026, 10, 4));
        assertThat(sunday).as("주말에는 업무 시간 서버를 켜지 않는다").doesNotContainKey(ids.get("office"));
        assertThat(sunday.get(ids.get("six-hours"))).isEqualTo(6 * 60);
    }

    @Test
    void 기간_시작_전과_아직_끝나지_않은_현재_시간은_넣지_않는다() {
        DemoFleet fleet = fleet(server("always", 1, DemoSchedule.ALWAYS, 0));
        String id = fleet.instances().getFirst().instanceId();

        assertThat(minutesOn(fleet, LocalDate.of(2026, 9, 30))).isEmpty();
        assertThat(minutesOn(fleet, LocalDate.of(2026, 10, 8)).get(id)).as("00:00~11:59 KST").isEqualTo(12 * 60);
    }

    @Test
    void 같은_설정과_시각이면_항상_같은_데이터다() {
        Server s = server("web", 3, DemoSchedule.WEEKDAY_OFFICE, 0);
        Instant from = OCT_1.atStartOfDay(KST).toInstant();

        assertThat(fleet(s).hourlySamples(from, NOW)).isEqualTo(fleet(s).hourlySamples(from, NOW));
        assertThat(fleet(s).instances()).isEqualTo(fleet(s).instances());
    }

    @Test
    void 잘못된_설정은_거절한다() {
        assertThatThrownBy(() -> new Server("web", "x9.huge", Os.LINUX, 1, DemoSchedule.ALWAYS, 0))
            .hasMessageContaining("지원하지 않는 인스턴스 타입");
        assertThatThrownBy(() -> server("web", 0, DemoSchedule.ALWAYS, 0)).hasMessageContaining("대수");
        assertThatThrownBy(() -> server("web", 1, DemoSchedule.DAILY_HOURS, 25)).hasMessageContaining("하루 사용 시간");
        assertThatThrownBy(() -> server(" ", 1, DemoSchedule.ALWAYS, 0)).hasMessageContaining("서버 이름");
        assertThatThrownBy(() -> new DemoSetup(List.of(), OCT_1, OCT_1)).hasMessageContaining("서버는");
        assertThatThrownBy(() -> new DemoSetup(
                Collections.nCopies(6, server("web", 10, DemoSchedule.ALWAYS, 0)), OCT_1, OCT_1))
            .hasMessageContaining("50대");
        assertThatThrownBy(() -> new DemoSetup(List.of(server("web", 1, DemoSchedule.ALWAYS, 0)), OCT_1, OCT_1.minusDays(1)))
            .isInstanceOf(DomainValidationException.class);

        DemoSetup future = new DemoSetup(List.of(server("web", 1, DemoSchedule.ALWAYS, 0)), OCT_1, LocalDate.of(2026, 10, 9));
        assertThatThrownBy(() -> future.requireNotAfter(LocalDate.of(2026, 10, 8))).hasMessageContaining("오늘까지");
    }
}
