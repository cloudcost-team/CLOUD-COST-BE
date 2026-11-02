package com.cloudcost.application;

import com.cloudcost.application.Ec2UsageQueryService.InstanceRow;
import com.cloudcost.application.Ec2UsageQueryService.InstancesView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.demo.DemoFleet;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.CurrentRun;
import com.cloudcost.domain.usage.DateRange;
import com.cloudcost.domain.usage.UsageAggregator;
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

    /** 설정한 기간을 월별로 나눈 합계 */
    public UsageCostView<YearMonth> monthly(DemoSetup setup) {
        return query(setup, UsageAggregator::monthlyMinutes);
    }

    private <K> UsageCostView<K> query(
        DemoSetup setup,
        Function<Map<LocalDate, Map<String, Long>>, Map<K, Map<String, Long>>> grouping) {
        Instant now = clock.instant();
        ZoneId zone = settings.zone();
        DemoFleet fleet = fleet(setup, now);
        DateRange range = setup.range();
        Map<LocalDate, Map<String, Long>> daily = UsageAggregator.dailyMinutes(
            fleet.hourlySamples(range.startInclusive(zone), range.endExclusive(zone)), zone, range.from(), range.to());
        Map<String, InstancePrice> prices = prices(fleet);
        CostReport<K> report = CostEstimator.report(grouping.apply(daily), prices);
        return new UsageCostView<>(
            ACCOUNT_ID, range, report, tracked(fleet, now), prices, setup.from(), FRESHNESS, notes());
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
