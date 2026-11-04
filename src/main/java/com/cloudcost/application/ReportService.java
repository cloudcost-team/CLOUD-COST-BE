package com.cloudcost.application;

import com.cloudcost.application.Ec2UsageQueryService.DailyView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.WasteService.WasteView;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.report.MonthlyReport;
import com.cloudcost.domain.report.MonthlyReport.Share;
import com.cloudcost.domain.report.MonthlyReport.Status;
import com.cloudcost.domain.spike.SpikeDetector.Spike;
import com.cloudcost.domain.usage.DateRange;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 월간 리포트 (이슈 #37, S10). 저장하지 않고 조회할 때 일별·월별 비용(#21)·급증일(#29)·낭비 탐지(#10)로 계산한다.
 * 실제 연결과 체험 모드가 같은 {@link #monthRows}·{@link #composeDetail}을 쓴다.
 */
public class ReportService {

    /** 목록에 보여 주는 최대 달 수 (이번 달 포함, 조회 기간 455일 제한 안) */
    public static final int MAX_MONTHS = 14;
    public static final String SAVINGS_BASIS =
        "절감 제안은 그 달 마지막 14일에 낭비 리소스와 같은 규칙(과다 사양·유휴)을 적용한 결과예요. 지금 남아 있는 서버만 판단해요.";
    public static final String NETWORK_LIMIT =
        "네트워크 지표는 최근 14일만 모으므로, 지난 달에는 유휴(네트워크 거의 없음) 판단이 빠질 수 있어요.";

    /**
     * @param costUsd         그 달 추정 비용 (단가를 아는 서버가 없으면 null)
     * @param previousCostUsd 전월 추정 비용. 전월이 기록 시작 전이면 null
     */
    public record MonthRow(
        YearMonth month, Status status, long usedMinutes, BigDecimal costUsd, BigDecimal previousCostUsd,
        BigDecimal changeRate) {
    }

    public record ListView(
        UUID accountId, LocalDate availableFrom, List<MonthRow> months, Freshness freshness, List<String> notes,
        Instant asOf) {
    }

    /**
     * @param from         그 달 리포트의 첫날 (기록 시작일이 그 달 안이면 기록 시작일)
     * @param to           마지막 날 (이번 달이면 오늘)
     * @param spikes       그 달의 급증일 (날짜순). EMPTY면 빈 목록
     * @param savings      그 달 마지막 14일 낭비 탐지 결과. EMPTY면 null
     * @param topServers   비용 상위 3대 + 기타
     * @param names        instanceId → 이름·타입 표시용
     */
    public record Detail(
        UUID accountId,
        MonthRow row,
        LocalDate from,
        LocalDate to,
        List<Spike> spikes,
        WasteView savings,
        List<Share> topServers,
        Map<String, TrackedInstance> names,
        Map<String, String> spikeCauseNames,
        Freshness freshness,
        List<String> notes,
        Instant asOf) {
    }

    private final Ec2UsageQueryService queries;
    private final WasteService waste;
    private final DemoUsageService demo;
    private final Clock clock;
    private final UsageSettings settings;

    public ReportService(
        Ec2UsageQueryService queries, WasteService waste, DemoUsageService demo, Clock clock, UsageSettings settings) {
        this.queries = queries;
        this.waste = waste;
        this.demo = demo;
        this.clock = clock;
        this.settings = settings;
    }

    public ListView list(String ownerId, UUID accountId) {
        YearMonth current = YearMonth.from(today());
        UsageCostView<YearMonth> monthly =
            queries.monthly(ownerId, accountId, current.minusMonths(MAX_MONTHS - 1L), current);
        return new ListView(accountId, monthly.availableFrom(), monthRows(monthly, current), monthly.freshness(),
            listNotes(monthly), clock.instant());
    }

    public Detail detail(String ownerId, UUID accountId, YearMonth month) {
        LocalDate today = today();
        YearMonth current = YearMonth.from(today);
        requireNotFuture(month, current);
        UsageCostView<YearMonth> monthly = queries.monthly(ownerId, accountId, month.minusMonths(1), month);
        MonthRow row = row(monthly, month, current);
        if (row.status() == Status.EMPTY) {
            return composeDetail(accountId, row, today, monthly, null, null, clock.instant());
        }
        DailyView daily = queries.dailyWithSpikes(
            ownerId, accountId, firstDay(month, monthly.availableFrom()), MonthlyReport.lastDay(month, today));
        WasteView savings = waste.wasteUntil(ownerId, accountId, nextMonthStart(month));
        return composeDetail(accountId, row, today, monthly, daily, savings, clock.instant());
    }

    /** 체험 모드: 설정한 기간의 마지막 날(to)을 오늘로 보고, 기간 안의 달을 보여 준다 */
    public ListView demoList(DemoSetup setup) {
        YearMonth current = YearMonth.from(setup.to());
        UsageCostView<YearMonth> monthly = demo.monthly(setup);
        return new ListView(DemoUsageService.ACCOUNT_ID, monthly.availableFrom(), monthRows(monthly, current),
            monthly.freshness(), listNotes(monthly), clock.instant());
    }

    public Detail demoDetail(DemoSetup setup, YearMonth month) {
        LocalDate today = setup.to();
        YearMonth current = YearMonth.from(today);
        requireNotFuture(month, current);
        UsageCostView<YearMonth> monthly =
            demo.monthly(setup, new DateRange(month.minusMonths(1).atDay(1), month.atEndOfMonth().isAfter(today)
                ? today : month.atEndOfMonth()));
        MonthRow row = row(monthly, month, current);
        if (row.status() == Status.EMPTY) {
            return composeDetail(DemoUsageService.ACCOUNT_ID, row, today, monthly, null, null, clock.instant());
        }
        DailyView daily = demo.dailyWithSpikes(setup,
            new DateRange(firstDay(month, monthly.availableFrom()), MonthlyReport.lastDay(month, today)));
        WasteView savings = demo.wasteUntil(setup, nextMonthStart(month));
        return composeDetail(DemoUsageService.ACCOUNT_ID, row, today, monthly, daily, savings, clock.instant());
    }

    /** 기록 시작 달 ~ 이번 달, 최신 달 먼저 */
    static List<MonthRow> monthRows(UsageCostView<YearMonth> monthly, YearMonth current) {
        List<MonthRow> rows = new ArrayList<>();
        if (monthly.availableFrom() == null) {
            return rows;
        }
        YearMonth first = YearMonth.from(monthly.availableFrom());
        for (PeriodCost<YearMonth> p : monthly.report().periods()) {
            if (!p.period().isBefore(first) && !p.period().isAfter(current)) {
                rows.add(row(monthly, p.period(), current));
            }
        }
        return List.copyOf(rows.reversed());
    }

    static MonthRow row(UsageCostView<YearMonth> monthly, YearMonth month, YearMonth current) {
        Map<YearMonth, PeriodCost<YearMonth>> byMonth = new HashMap<>();
        monthly.report().periods().forEach(p -> byMonth.put(p.period(), p));
        PeriodCost<YearMonth> p = byMonth.get(month);
        long minutes = p == null ? 0 : p.usedMinutes();
        YearMonth first = monthly.availableFrom() == null ? null : YearMonth.from(monthly.availableFrom());
        Status status = MonthlyReport.status(month, current, first, minutes);
        BigDecimal cost = p == null ? BigDecimal.ZERO : p.estimatedCost();
        BigDecimal previous = null;
        PeriodCost<YearMonth> prev = byMonth.get(month.minusMonths(1));
        if (first != null && month.isAfter(first) && prev != null) {
            previous = prev.estimatedCost();
        }
        return new MonthRow(month, status, minutes, cost, previous, MonthlyReport.changeRate(cost, previous));
    }

    static Detail composeDetail(
        UUID accountId, MonthRow row, LocalDate today, UsageCostView<YearMonth> monthly, DailyView daily,
        WasteView savings, Instant asOf) {
        LocalDate from = firstDay(row.month(), monthly.availableFrom());
        LocalDate to = MonthlyReport.lastDay(row.month(), today);
        List<String> notes = new ArrayList<>(monthly.notes());
        List<Spike> spikes = new ArrayList<>();
        Map<String, String> causeNames = new HashMap<>();
        List<Share> top = List.of();
        if (daily != null) {
            daily.spikes().values().stream().sorted((a, b) -> a.date().compareTo(b.date())).forEach(s -> {
                spikes.add(s);
                if (s.causeInstanceId() != null) {
                    causeNames.put(s.causeInstanceId(), daily.causeName(s));
                }
            });
        }
        PeriodCost<YearMonth> period = monthly.report().periods().stream()
            .filter(p -> p.period().equals(row.month())).findFirst().orElse(null);
        if (period != null) {
            top = MonthlyReport.topServers(period.instances());
        }
        switch (row.status()) {
            case PREVIEW -> notes.add("이번 달은 아직 끝나지 않아 오늘까지의 값이에요. 달이 끝나면 확정돼요.");
            case FIRST_MONTH -> notes.add("기록이 시작된 첫 달이라 전월과 비교하지 않아요. " + from + "부터의 기록이에요.");
            case EMPTY -> notes.add("이 달에는 사용 기록이 없어요.");
            default -> {
            }
        }
        if (savings != null) {
            notes.add(SAVINGS_BASIS);
            notes.add(NETWORK_LIMIT);
        }
        return new Detail(accountId, row, from, to, List.copyOf(spikes), savings, top, monthly.instances(), causeNames,
            daily == null ? monthly.freshness() : daily.costs().freshness(), List.copyOf(notes), asOf);
    }

    private static List<String> listNotes(UsageCostView<YearMonth> monthly) {
        List<String> notes = new ArrayList<>(monthly.notes());
        notes.add("리포트는 저장하지 않고 조회할 때 계산해요. 지난 달 값도 단가·수집 기록이 바뀌면 달라질 수 있어요.");
        return notes;
    }

    private static LocalDate firstDay(YearMonth month, LocalDate availableFrom) {
        LocalDate start = month.atDay(1);
        return availableFrom != null && availableFrom.isAfter(start) && YearMonth.from(availableFrom).equals(month)
            ? availableFrom : start;
    }

    private Instant nextMonthStart(YearMonth month) {
        ZoneId zone = settings.zone();
        return month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
    }

    private static void requireNotFuture(YearMonth month, YearMonth current) {
        if (month.isAfter(current)) {
            throw new DomainValidationException("INVALID_PERIOD", "아직 오지 않은 달의 리포트는 볼 수 없어요.");
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), settings.zone());
    }
}
