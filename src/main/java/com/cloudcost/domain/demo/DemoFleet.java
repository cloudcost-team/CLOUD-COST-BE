package com.cloudcost.domain.demo;

import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.demo.DemoSetup.Server;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 체험 모드: 사용자가 정한 서버 구성({@link DemoSetup})을 실제 수집 결과와 같은 모양
 * (DescribeInstances의 인스턴스 + CloudWatch 시간별 샘플: SampleCount·CPU 평균·최대·네트워크)으로 만든다. DB에 저장하지 않는다.
 *
 * <p>모든 서버는 기간 시작일 0시에 처음 켰다고 본다. 같은 설정·시각이면 항상 같은 결과다(무작위 없음).
 */
public final class DemoFleet {

    public static final String OWNER_ID = "demo";
    public static final String REGION = "ap-northeast-2";
    public static final String NOTE =
        "체험용 계정의 예시 데이터입니다. 설정한 서버 구성으로 계산했으며 실제 AWS 계정의 사용량이 아닙니다.";
    public static final String PRICE_NOTE =
        "체험 모드 단가는 서울 리전(ap-northeast-2) 온디맨드 단가를 고정값으로 넣은 것입니다.";

    private static final BigDecimal FULL_HOUR = BigDecimal.valueOf(60);

    /** 펼친 인스턴스 1대 */
    private record Unit(String instanceId, String name, Server server) {
    }

    private final List<Unit> units;
    private final Instant start;
    private final Instant now;
    private final ZoneId zone;

    public DemoFleet(DemoSetup setup, Instant now, ZoneId zone) {
        this.units = expand(setup.servers());
        this.start = setup.from().atStartOfDay(zone).toInstant();
        this.now = now;
        this.zone = zone;
    }

    public List<Ec2Instance> instances() {
        return units.stream().map(this::instance).toList();
    }

    public List<InstancePrice> prices() {
        return units.stream()
            .map(u -> InstancePrice.of(
                u.instanceId(),
                DemoCatalog.find(u.server().instanceType()).orElseThrow().hourlyUsd(u.server().os()),
                false))
            .toList();
    }

    /**
     * CloudWatch 시간별 샘플. SampleCount는 켜져 있던 시간마다 60(1분 샘플 60개)이고,
     * CPU·네트워크는 서버의 사용 모양({@link DemoUsagePattern})으로 만든다.
     * 기간 시작 전과 아직 끝나지 않은 현재 시간은 넣지 않는다.
     */
    public List<HourlySample> hourlySamples(Instant from, Instant to) {
        Instant first = latest(from.truncatedTo(ChronoUnit.HOURS), start);
        Instant end = earliest(to, currentHour());
        List<HourlySample> samples = new ArrayList<>();
        for (Unit u : units) {
            for (Instant h = first; h.isBefore(end); h = h.plus(1, ChronoUnit.HOURS)) {
                if (isOn(u, h)) {
                    samples.add(sample(u, h));
                }
            }
        }
        return samples;
    }

    private HourlySample sample(Unit u, Instant hour) {
        DemoUsagePattern.HourMetrics m = u.server().usage().at(hour.atZone(zone), jitter(u.instanceId(), hour));
        return new HourlySample(
            u.instanceId(), hour, FULL_HOUR, percent(m.cpuAvg()), percent(m.cpuMax()), m.networkInBytes(),
            m.networkOutBytes());
    }

    /** 인스턴스 ID와 시각으로 정한 0 이상 1 미만의 값 (SplitMix64). 같은 입력이면 항상 같다. */
    static double jitter(String instanceId, Instant hour) {
        long z = instanceId.hashCode() * 0x9E3779B97F4A7C15L + hour.getEpochSecond();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    private static BigDecimal percent(double value) {
        return BigDecimal.valueOf(Math.min(100, value)).setScale(3, RoundingMode.HALF_UP);
    }

    private static List<Unit> expand(List<Server> servers) {
        List<Unit> result = new ArrayList<>();
        for (Server s : servers) {
            for (int i = 1; i <= s.count(); i++) {
                String name = s.count() == 1 ? s.name() : s.name() + "-" + i;
                result.add(new Unit(instanceId(result.size() + 1), name, s));
            }
        }
        return result;
    }

    /** i-0d3e + 13자리 16진수 (실제 인스턴스 ID 형식: i- + 17자리) */
    private static String instanceId(int seq) {
        return "i-0d3e%013x".formatted(seq);
    }

    private Ec2Instance instance(Unit u) {
        Optional<Instant> lastOn = lastOnHour(u);
        boolean running = lastOn.filter(h -> h.equals(currentHour())).isPresent();
        Instant launchTime = lastOn.map(h -> blockStart(u, h)).orElse(start);
        return new Ec2Instance(
            u.instanceId(), u.name(), u.server().instanceType(), REGION, REGION + "a", running ? "running" : "stopped",
            launchTime, u.server().os().platformDetails(), "default", false, false);
    }

    private boolean isOn(Unit u, Instant hour) {
        return u.server().schedule().isOn(hour.atZone(zone), u.server().hoursPerDay());
    }

    /** 지금(현재 시간 포함) 또는 그 이전에 마지막으로 켜져 있던 시간. 일주일 안에 없으면 계속 꺼져 있던 것으로 본다. */
    private Optional<Instant> lastOnHour(Unit u) {
        Instant limit = latest(start, currentHour().minus(7, ChronoUnit.DAYS));
        for (Instant h = currentHour(); !h.isBefore(limit); h = h.minus(1, ChronoUnit.HOURS)) {
            if (isOn(u, h)) {
                return Optional.of(h);
            }
        }
        return Optional.empty();
    }

    /** onHour가 속한 연속 실행 구간의 시작 = 그 구간의 LaunchTime. 기간 시작보다 앞서지 않는다. */
    private Instant blockStart(Unit u, Instant onHour) {
        Instant h = onHour;
        while (h.isAfter(start) && isOn(u, h.minus(1, ChronoUnit.HOURS))) {
            h = h.minus(1, ChronoUnit.HOURS);
        }
        return h;
    }

    private Instant currentHour() {
        return now.truncatedTo(ChronoUnit.HOURS);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
