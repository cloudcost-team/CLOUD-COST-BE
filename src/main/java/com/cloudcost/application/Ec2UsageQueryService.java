package com.cloudcost.application;

import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.cost.CostEstimator;
import com.cloudcost.domain.cost.CostEstimator.CostReport;
import com.cloudcost.domain.cost.CostNotes;
import com.cloudcost.domain.cost.InstancePrice;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.spike.SpikeDetector;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import com.cloudcost.domain.usage.CurrentRun;
import com.cloudcost.domain.usage.DateRange;
import com.cloudcost.domain.usage.HourlySample;
import com.cloudcost.domain.usage.UsageAggregator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EC2 인스턴스·사용 시간·추정 비용 조회.
 *
 * <p>조회할 때마다 {@link Ec2DataRefresher}가 캐시 기간을 확인해 필요할 때만 AWS를 부르고,
 * 계산은 모두 domain에서 한다. 프론트는 결과를 표시만 한다.
 */
public class Ec2UsageQueryService {

    private final AwsAccountService accountService;
    private final Ec2DataRefresher refresher;
    private final Ec2InstanceRepository instances;
    private final Ec2UsageRepository usage;
    private final PriceService prices;
    private final Clock clock;
    private final UsageSettings settings;

    public Ec2UsageQueryService(
        AwsAccountService accountService,
        Ec2DataRefresher refresher,
        Ec2InstanceRepository instances,
        Ec2UsageRepository usage,
        PriceService prices,
        Clock clock,
        UsageSettings settings) {
        this.accountService = accountService;
        this.refresher = refresher;
        this.instances = instances;
        this.usage = usage;
        this.prices = prices;
        this.clock = clock;
        this.settings = settings;
    }

    public record InstanceRow(TrackedInstance instance, InstancePrice price, CurrentRun currentRun) {
    }

    public record InstancesView(UUID accountId, List<InstanceRow> rows, Freshness freshness, Instant asOf) {
    }

    /**
     * @param instances     instanceId → 인스턴스 (이름·타입 표시용)
     * @param availableFrom 이 날짜부터 사용량 기록이 있다 (처음 본 인스턴스의 소급 수집 시작일). 아직 인스턴스가 없으면 null
     */
    public record UsageCostView<K>(
        UUID accountId,
        DateRange range,
        CostReport<K> report,
        Map<String, TrackedInstance> instances,
        Map<String, InstancePrice> prices,
        LocalDate availableFrom,
        Freshness freshness,
        List<String> notes) {
    }

    /**
     * 일별 비용 + 급증일 (#29).
     *
     * @param spikes 날짜 → 급증. 급증이 아닌 날은 없다
     */
    public record DailyView(UsageCostView<LocalDate> costs, Map<LocalDate, Spike> spikes) {

        /** 원인 서버 이름 (Name 태그). 모르면 null */
        public String causeName(Spike spike) {
            if (spike.causeInstanceId() == null) {
                return null;
            }
            TrackedInstance t = costs.instances().get(spike.causeInstanceId());
            return t == null ? null : t.info().name();
        }
    }

    public InstancesView instances(String ownerId, UUID accountId) {
        accountService.getActive(ownerId, accountId);
        Freshness freshness = refresher.refreshIfStale(ownerId, accountId);
        Instant now = clock.instant();
        List<TrackedInstance> tracked = sorted(instances.findAllByOwnerIdAndAccountId(ownerId, accountId));
        PriceService.PriceResult priced = prices.pricesFor(tracked.stream().map(TrackedInstance::info).toList());
        List<InstanceRow> rows = tracked.stream()
            .map(t -> new InstanceRow(
                t, priced.byInstanceId().get(t.instanceId()), CurrentRun.of(t.info(), now).orElse(null)))
            .toList();
        return new InstancesView(accountId, rows, freshness.withWarnings(Freshness.WarningCode.PRICE_UNAVAILABLE, priced.warnings()), now);
    }

    /**
     * 기간 합계. 기본: 이번 달 1일 ~ 오늘
     */
    public UsageCostView<DateRange> summary(String ownerId, UUID accountId, LocalDate from, LocalDate to) {
        LocalDate today = today();
        DateRange range = DateRange.resolve(from, to, today.withDayOfMonth(1), today);
        return query(ownerId, accountId, range, daily -> {
            Map<DateRange, Map<String, Long>> single = new LinkedHashMap<>();
            single.put(range, UsageAggregator.totalMinutes(daily));
            return single;
        });
    }

    /**
     * 일별. 기본: 최근 30일 (오늘 포함)
     */
    public UsageCostView<LocalDate> daily(String ownerId, UUID accountId, LocalDate from, LocalDate to) {
        LocalDate today = today();
        DateRange range = DateRange.resolve(from, to, today.minusDays(29), today);
        return query(ownerId, accountId, range, Function.identity());
    }

    /**
     * 일별 + 급증일. 기간 앞 30일을 함께 읽어 첫날부터 평소 비용을 잰다 (기본: 최근 30일).
     */
    public DailyView dailyWithSpikes(String ownerId, UUID accountId, LocalDate from, LocalDate to) {
        LocalDate today = today();
        DateRange range = DateRange.resolve(from, to, today.minusDays(29), today);
        Loaded loaded = load(ownerId, accountId, range, range.from().minusDays(SpikeDetector.BASELINE_DAYS));
        CostReport<LocalDate> withBaseline = CostEstimator.report(loaded.daily(), loaded.priced().byInstanceId());
        LocalDate firstUsed = firstUsedDay(ownerId, accountId);
        Map<LocalDate, Spike> spikes = SpikeDetector.detect(withBaseline,
            SpikeDetector.baselineFrom(loaded.availableFrom(), firstUsed), range.from(), range.to());
        return new DailyView(view(loaded, Function.identity()), spikes);
    }

    /** 이 연결에서 처음 사용 기록이 있는 날 (기록 전체 기준). 기록이 없으면 null */
    public LocalDate firstUsedDay(String ownerId, UUID accountId) {
        return usage.findFirstUsedHour(ownerId, accountId)
            .map(h -> LocalDate.ofInstant(h, settings.zone()))
            .orElse(null);
    }

    /**
     * 월별 = 일별 합산. 기본: 최근 3개월 (이번 달 포함)
     */
    public UsageCostView<YearMonth> monthly(String ownerId, UUID accountId, YearMonth from, YearMonth to) {
        YearMonth thisMonth = YearMonth.from(today());
        YearMonth start = from == null ? thisMonth.minusMonths(2) : from;
        YearMonth end = to == null ? thisMonth : to;
        DateRange range = new DateRange(start.atDay(1), end.atEndOfMonth());
        return query(ownerId, accountId, range, UsageAggregator::monthlyMinutes);
    }

    private <K> UsageCostView<K> query(
        String ownerId,
        UUID accountId,
        DateRange range,
        Function<Map<LocalDate, Map<String, Long>>, Map<K, Map<String, Long>>> grouping) {
        return view(load(ownerId, accountId, range, range.from()), grouping);
    }

    /**
     * @param daily 읽어 온 날짜 전체(loadFrom ~ range.to) → instanceId → 사용 분
     */
    private record Loaded(
        UUID accountId,
        DateRange range,
        Map<LocalDate, Map<String, Long>> daily,
        Map<String, TrackedInstance> tracked,
        PriceService.PriceResult priced,
        LocalDate availableFrom,
        Freshness freshness) {
    }

    /** range 안의 날만 grouping해 비용을 계산한다 */
    private <K> UsageCostView<K> view(
        Loaded l, Function<Map<LocalDate, Map<String, Long>>, Map<K, Map<String, Long>>> grouping) {
        Map<LocalDate, Map<String, Long>> inRange = new LinkedHashMap<>();
        l.daily().forEach((day, minutes) -> {
            if (!day.isBefore(l.range().from())) {
                inRange.put(day, minutes);
            }
        });
        CostReport<K> report = CostEstimator.report(grouping.apply(inRange), l.priced().byInstanceId());
        return new UsageCostView<>(
            l.accountId(), l.range(), report, l.tracked(), l.priced().byInstanceId(), l.availableFrom(),
            l.freshness().withWarnings(Freshness.WarningCode.PRICE_UNAVAILABLE, l.priced().warnings()), notes(report, l.range(), l.availableFrom()));
    }

    /**
     * @param loadFrom range.from 이하. 그 앞 날짜(급증 판정의 평소 비용용)도 함께 읽는다
     */
    private Loaded load(String ownerId, UUID accountId, DateRange range, LocalDate loadFrom) {
        accountService.getActive(ownerId, accountId);
        Freshness freshness = refresher.refreshIfStale(ownerId, accountId);
        ZoneId zone = settings.zone();

        List<HourlySample> samples = usage.findHourly(
            ownerId, accountId, loadFrom.atStartOfDay(zone).toInstant(), range.endExclusive(zone));
        Map<LocalDate, Map<String, Long>> daily = UsageAggregator.dailyMinutes(samples, zone, loadFrom, range.to());

        Map<String, TrackedInstance> tracked = instances.findAllByOwnerIdAndAccountId(ownerId, accountId).stream()
            .collect(Collectors.toMap(TrackedInstance::instanceId, Function.identity()));
        PriceService.PriceResult priced =
            prices.pricesFor(tracked.values().stream().map(TrackedInstance::info).toList());
        return new Loaded(accountId, range, daily, tracked, priced, availableFrom(tracked.values()), freshness);
    }

    /**
     * 사용량 기록이 시작되는 날: 가장 먼저 본 인스턴스의 첫 수집 때 소급한 시작일 (처음 본 시각 − backfill).
     * 그보다 앞의 기간은 조회해도 0이다.
     */
    private LocalDate availableFrom(Collection<TrackedInstance> tracked) {
        return availableFrom(tracked, settings);
    }

    static LocalDate availableFrom(Collection<TrackedInstance> tracked, UsageSettings settings) {
        return tracked.stream()
            .map(TrackedInstance::firstSeenAt)
            .min(Comparator.naturalOrder())
            .map(first -> LocalDate.ofInstant(first.minus(settings.backfill()), settings.zone()))
            .orElse(null);
    }

    private List<String> notes(CostReport<?> report, DateRange range, LocalDate availableFrom) {
        List<String> notes = new ArrayList<>();
        notes.add(CostNotes.ESTIMATE);
        notes.add(CostNotes.EXCLUDED);
        notes.add(CostNotes.USAGE_METHOD);
        notes.add(CostNotes.HISTORY_LIMIT + " (첫 수집 때 최근 " + settings.backfill().toDays() + "일을 소급합니다.)");
        if (availableFrom != null && range.from().isBefore(availableFrom)) {
            notes.add(CostNotes.noHistoryBefore(availableFrom));
        }
        if (!report.unpricedInstanceIds().isEmpty()) {
            BigDecimal hours = CostEstimator.hours(report.unpricedMinutes()).setScale(2, RoundingMode.HALF_UP);
            notes.add("단가를 찾지 못한 인스턴스 " + report.unpricedInstanceIds().size() + "개(사용 " + hours
                + "시간)는 추정 비용에 포함되지 않았습니다.");
        }
        return notes;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), settings.zone());
    }

    /** 남아 있는 인스턴스 → 실행 중 → instanceId 순 */
    static List<TrackedInstance> sorted(List<TrackedInstance> list) {
        return list.stream()
            .sorted(Comparator.comparing(TrackedInstance::present).reversed()
                .thenComparing(t -> t.info().isRunning() ? 0 : 1)
                .thenComparing(TrackedInstance::instanceId))
            .toList();
    }
}
