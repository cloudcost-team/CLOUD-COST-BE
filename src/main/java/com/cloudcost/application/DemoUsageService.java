package com.cloudcost.application;

import com.cloudcost.application.Ec2UsageQueryService.DailyView;
import com.cloudcost.application.Ec2UsageQueryService.InstanceRow;
import com.cloudcost.application.Ec2UsageQueryService.InstancesView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.demo.DemoCatalog;
import com.cloudcost.domain.demo.DemoCatalog.Os;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceSpecs;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.spike.SpikeDetector;
import com.cloudcost.domain.usage.CurrentRun;
import com.cloudcost.domain.usage.DateRange;
import com.cloudcost.domain.usage.UsageAggregator;
import com.cloudcost.domain.usage.UtilizationStats;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 체험 모드: 실제 AWS 계정 없이, 간이 로그인 후 사용자가 정한 서버 구성으로
 * 실제 연결 계정과 같은 집계·비용 계산을 거쳐 같은 모양의 결과를 만든다.
 *
 * <p>AWS와 DB를 부르지 않는다. 체험용 계정은 연결 목록(aws_accounts)에 들어가지 않는다 (ADR-008).
 */
public class DemoUsageService {

    /** 체험용 계정 id. 실제 연결 id와 겹치지 않는 고정값이다. */
    public static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-4000-8000-00000000d3e0");
    public static final String AWS_ACCOUNT_ID = "123456789012";
    public static final String ACCOUNT_NAME = "체험용 AWS 계정";

    private static final Freshness FRESHNESS = new Freshness(null, false, null, false, null, List.of());

    /** 간이 로그인 계정. 실제 AWS 자격증명이 아니라 체험 화면에 안내하는 공개 값이다. */
    public record DemoSettings(String username, String password) {
    }

    public record DemoAccount(UUID accountId, String name, String awsAccountId, String region) {
    }

    private final Clock clock;
    private final UsageSettings settings;
    private final DemoSettings demo;

    public DemoUsageService(Clock clock, UsageSettings settings, DemoSettings demo) {
        this.clock = clock;
        this.settings = settings;
        this.demo = demo;
    }

    public DemoAccount login(String username, String password) {
        if (!matches(username, demo.username()) || !matches(password, demo.password())) {
            throw new AuthenticationFailedException("DEMO_LOGIN_FAILED", "아이디 또는 비밀번호가 맞지 않습니다.");
        }
        return new DemoAccount(ACCOUNT_ID, ACCOUNT_NAME, AWS_ACCOUNT_ID, DemoFleet.REGION);
    }

    public InstancesView instances(DemoSetup setup) {
        Instant now = clock.instant();
        DemoFleet fleet = fleet(setup, now);
        Map<String, InstancePrice> prices = prices(fleet);
        List<InstanceRow> rows = Ec2UsageQueryService.sorted(List.copyOf(tracked(fleet, now).values())).stream()
            .map(t -> new InstanceRow(
                t, prices.get(t.instanceId()), CurrentRun.of(t.info(), now).orElse(null)))
            .toList();
        return new InstancesView(ACCOUNT_ID, rows, FRESHNESS, now);
    }

    /** 설정한 기간 전체 합계 */
    public UsageCostView<DateRange> summary(DemoSetup setup) {
        DateRange range = setup.range();
        return query(setup, daily -> {
            Map<DateRange, Map<String, Long>> single = new LinkedHashMap<>();
            single.put(range, UsageAggregator.totalMinutes(daily));
            return single;
        });
    }

    public UsageCostView<LocalDate> daily(DemoSetup setup) {
        return query(setup, Function.identity());
    }

    /** 일별 + 급증일. 기록 시작일(from)부터 평소 비용을 잰다 */
    public DailyView dailyWithSpikes(DemoSetup setup) {
        return withSpikes(daily(setup));
    }

    /**
     * 지정한 기간의 일별 + 급증일 (대시보드용). 실제 연결처럼 기간 앞 30일도 읽어 기간 첫날부터 평소 비용을 잰다
     * (설정한 기간이 길면 대시보드 기간 앞에도 기록이 있다).
     */
    public DailyView dailyWithSpikes(DemoSetup setup, DateRange range) {
        UsageCostView<LocalDate> view = daily(setup, range);
        UsageCostView<LocalDate> withBaseline =
            daily(setup, new DateRange(range.from().minusDays(SpikeDetector.BASELINE_DAYS), range.to()));
        return new DailyView(view, SpikeDetector.detect(
            withBaseline.report(), withBaseline.availableFrom(), range.from(), range.to()));
    }

    private static DailyView withSpikes(UsageCostView<LocalDate> view) {
        return new DailyView(view, SpikeDetector.detect(
            view.report(), view.availableFrom(), view.range().from(), view.range().to()));
    }

    /** 설정한 기간을 월별로 나눈 합계 */
    public UsageCostView<YearMonth> monthly(DemoSetup setup) {
        return query(setup, UsageAggregator::monthlyMinutes);
    }

    /** 지정한 기간을 월별로 나눈 합계 (서버는 여전히 setup.from 0시에 처음 켠 것으로 본다) */
    public UsageCostView<YearMonth> monthly(DemoSetup setup, DateRange range) {
        return query(setup, range, UsageAggregator::monthlyMinutes);
    }

    /**
     * 설정한 서버 구성으로 지정한 기간의 일별 비용을 계산한다. 서버는 여전히 setup.from 0시에 처음 켠 것으로 보므로
     * 그 앞의 날은 0이다 (대시보드의 전월 비교용).
     */
    public UsageCostView<LocalDate> daily(DemoSetup setup, DateRange range) {
        return query(setup, range, Function.identity());
    }

    private <K> UsageCostView<K> query(
        DemoSetup setup,
        Function<Map<LocalDate, Map<String, Long>>, Map<K, Map<String, Long>>> grouping) {
        return query(setup, setup.range(), grouping);
    }

    private <K> UsageCostView<K> query(
        DemoSetup setup,
        DateRange range,
        Function<Map<LocalDate, Map<String, Long>>, Map<K, Map<String, Long>>> grouping) {
        Instant now = clock.instant();
        ZoneId zone = settings.zone();
        DemoFleet fleet = fleet(setup, now);
        Map<LocalDate, Map<String, Long>> daily = UsageAggregator.dailyMinutes(
            fleet.hourlySamples(range.startInclusive(zone), range.endExclusive(zone)), zone, range.from(), range.to());
        Map<String, InstancePrice> prices = prices(fleet);
        CostReport<K> report = CostEstimator.report(grouping.apply(daily), prices);
        return new UsageCostView<>(
            ACCOUNT_ID, range, report, tracked(fleet, now), prices, setup.from(), FRESHNESS, notes());
    }

    /**
     * 체험 모드 낭비 탐지: 설정한 기간의 마지막 14일(아직 지나지 않은 시간 제외)을 서버별 사용 모양으로 판단한다.
     * 기간이 14일보다 짧으면 INSUFFICIENT_HISTORY (availableAt = from + 14일).
     */
    public WasteService.WasteView waste(DemoSetup setup) {
        return wasteUntil(setup, setup.range().endExclusive(settings.zone()));
    }

    /**
     * until 직전 14일로 판단한다 (체험 월간 리포트). 설정한 기간 끝·지금 정시보다 늦으면 그 시각으로 줄인다.
     */
    public WasteService.WasteView wasteUntil(DemoSetup setup, Instant until) {
        Instant now = clock.instant();
        ZoneId zone = settings.zone();
        DemoFleet fleet = fleet(setup, now);
        Instant periodEnd = setup.range().endExclusive(zone);
        Instant currentHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        Instant end = periodEnd.isBefore(currentHour) ? periodEnd : currentHour;
        if (until.isBefore(end)) {
            end = until;
        }
        Instant start = end.minus(UtilizationStats.WINDOW);
        List<Ec2Instance> infos = fleet.instances();
        return WasteService.analyze(ACCOUNT_ID, infos, fleet.hourlySamples(start, end), start, end, setup.from(),
            i -> demoHourly(i.instanceType(), i.platformDetails()),
            i -> {
                Map<String, java.math.BigDecimal> prices = new java.util.HashMap<>();
                InstanceSpecs.downsizeCandidates(i.instanceType()).forEach(t -> {
                    java.math.BigDecimal price = demoHourly(t.instanceType(), i.platformDetails());
                    if (price != null) {
                        prices.put(t.instanceType(), price);
                    }
                });
                return prices;
            },
            FRESHNESS, now, settings);
    }

    /** 체험 모드 고정 단가. 카탈로그에 없는 타입이면 null */
    private static java.math.BigDecimal demoHourly(String instanceType, String platformDetails) {
        Os os = Os.WINDOWS.platformDetails().equals(platformDetails) ? Os.WINDOWS : Os.LINUX;
        return DemoCatalog.find(instanceType).map(t -> t.hourlyUsd(os)).orElse(null);
    }

    private DemoFleet fleet(DemoSetup setup, Instant now) {
        setup.requireNotAfter(LocalDate.ofInstant(now, settings.zone()));
        return new DemoFleet(setup, now, settings.zone());
    }

    private static Map<String, TrackedInstance> tracked(DemoFleet fleet, Instant now) {
        Map<String, TrackedInstance> result = new LinkedHashMap<>();
        for (Ec2Instance info : fleet.instances()) {
            UUID id = UUID.nameUUIDFromBytes(info.instanceId().getBytes(StandardCharsets.UTF_8));
            result.put(info.instanceId(), TrackedInstance.firstSeen(id, DemoFleet.OWNER_ID, ACCOUNT_ID, info, now));
        }
        return result;
    }

    private static Map<String, InstancePrice> prices(DemoFleet fleet) {
        return fleet.prices().stream().collect(Collectors.toMap(InstancePrice::instanceId, Function.identity()));
    }

    private static List<String> notes() {
        return List.of(DemoFleet.NOTE, DemoFleet.PRICE_NOTE, CostNotes.ESTIMATE, CostNotes.EXCLUDED, CostNotes.USAGE_METHOD);
    }

    private static boolean matches(String given, String expected) {
        return given != null && MessageDigest.isEqual(
            given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
