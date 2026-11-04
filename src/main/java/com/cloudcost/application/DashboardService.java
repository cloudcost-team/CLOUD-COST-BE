package com.cloudcost.application;

import com.cloudcost.application.Ec2UsageQueryService.InstanceRow;
import com.cloudcost.application.Ec2UsageQueryService.InstancesView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.domain.budget.MonthEndForecast;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import com.cloudcost.domain.cost.DisplayCurrency.ExchangeRate;
import com.cloudcost.domain.demo.DemoSetup;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.InstanceStates;
import com.cloudcost.domain.usage.DateRange;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 연결별 월 예산과 대시보드 상단 요약 (이슈 #27, S06 카드 4개).
 * 실제 연결과 체험 모드가 같은 {@link #compose} 계산을 쓴다. 금액은 USD로 계산하고 응답에서 환산한다.
 */
public class DashboardService {

    /** 예산 알림 기준 (월말 예상 기준, %) */
    public static final List<Integer> THRESHOLDS = List.of(80, 100, 120);

    /**
     * @param costUsd   기간 추정 비용
     * @param usedMinutes 기간 사용 분
     */
    public record PeriodTotal(LocalDate from, LocalDate to, long usedMinutes, BigDecimal costUsd) {
    }

    /**
     * 이번 달 1일~어제 vs 전월 1일~같은 날. 둘 다 끝난 날끼리 비교한다.
     *
     * @param changeRate 증감률 (%). 전월이 0이면 null
     */
    public record PreviousComparison(
        PeriodTotal current, PeriodTotal previous, BigDecimal changeUsd, BigDecimal changeRate) {
    }

    /**
     * @param budgetUsd        비교용 USD 예산. 예산이 없거나 원화 예산인데 환율이 없으면 null
     * @param budgetRate       월말 예상 ÷ 예산 (%). 계산할 수 없으면 null
     * @param thresholdReached 넘은 가장 높은 알림 기준 (80·100·120). 없으면 null
     */
    public record BudgetStatus(
        MonthlyBudget budget, BigDecimal budgetUsd, BigDecimal budgetRate, Integer thresholdReached) {
    }

    public record DashboardView(
        UUID accountId,
        LocalDate today,
        YearMonth month,
        LocalDate availableFrom,
        PeriodTotal monthToDate,
        int runningCount,
        int stoppedCount,
        MonthEndForecast forecast,
        BudgetStatus budget,
        PreviousComparison previous,
        Freshness freshness,
        List<String> notes,
        Instant asOf) {
    }

    private final AwsAccountService accountService;
    private final Ec2UsageQueryService queries;
    private final DemoUsageService demo;
    private final BudgetRepository budgets;
    private final CurrencyService currencies;
    private final Clock clock;
    private final UsageSettings settings;

    public DashboardService(
        AwsAccountService accountService,
        Ec2UsageQueryService queries,
        DemoUsageService demo,
        BudgetRepository budgets,
        CurrencyService currencies,
        Clock clock,
        UsageSettings settings) {
        this.accountService = accountService;
        this.queries = queries;
        this.demo = demo;
        this.budgets = budgets;
        this.currencies = currencies;
        this.clock = clock;
        this.settings = settings;
    }

    public MonthlyBudget setBudget(String ownerId, UUID accountId, MonthlyBudget budget) {
        accountService.get(ownerId, accountId);
        budgets.save(ownerId, accountId, budget, clock.instant());
        return budget;
    }

    public void deleteBudget(String ownerId, UUID accountId) {
        accountService.get(ownerId, accountId);
        budgets.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    public DashboardView dashboard(String ownerId, UUID accountId) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), settings.zone());
        UsageCostView<LocalDate> daily =
            queries.daily(ownerId, accountId, YearMonth.from(today).minusMonths(1).atDay(1), today);
        InstancesView instances = queries.instances(ownerId, accountId);
        List<Ec2Instance> present = instances.rows().stream()
            .map(InstanceRow::instance)
            .filter(t -> t.present())
            .map(t -> t.info())
            .toList();
        return compose(accountId, today, daily, present,
            budgets.findByOwnerIdAndAccountId(ownerId, accountId).orElse(null), krwRate(),
            daily.freshness().withWarnings(instances.freshness().warnings()), clock.instant());
    }

    /**
     * 체험 모드: 설정한 기간의 마지막 날(to)을 오늘로 보고 계산한다. 전월은 서버를 켜기 전이라 0이므로
     * 기록 시작일(from)이 전월 1일보다 늦으면 전월 비교를 하지 않는다.
     */
    public DashboardView demoDashboard(DemoSetup setup, MonthlyBudget budget) {
        LocalDate today = setup.to();
        UsageCostView<LocalDate> daily =
            demo.daily(setup, new DateRange(YearMonth.from(today).minusMonths(1).atDay(1), today));
        List<Ec2Instance> present = demo.instances(setup).rows().stream().map(r -> r.instance().info()).toList();
        return compose(DemoUsageService.ACCOUNT_ID, today, daily, present, budget, krwRate(), daily.freshness(),
            clock.instant());
    }

    /**
     * @param daily 전월 1일 ~ 오늘의 일별 비용 (availableFrom 포함)
     */
    static DashboardView compose(
        UUID accountId, LocalDate today, UsageCostView<LocalDate> daily, List<Ec2Instance> present,
        MonthlyBudget budget, ExchangeRate krwRate, Freshness freshness, Instant asOf) {
        Map<LocalDate, BigDecimal> costByDay = new HashMap<>();
        Map<LocalDate, Long> minutesByDay = new HashMap<>();
        for (PeriodCost<LocalDate> p : daily.report().periods()) {
            costByDay.put(p.period(), p.estimatedCost() == null ? BigDecimal.ZERO : p.estimatedCost());
            minutesByDay.put(p.period(), p.usedMinutes());
        }
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate availableFrom = daily.availableFrom();
        PeriodTotal monthToDate = total(monthStart, today, costByDay, minutesByDay);

        MonthEndForecast forecast = MonthEndForecast.of(costByDay, today, availableFrom).orElse(null);
        List<String> notes = new ArrayList<>(daily.notes());
        notes.add(MonthEndForecast.METHOD);
        if (forecast == null) {
            notes.add("끝난 날의 기록이 없어 월말 예상 비용은 내일부터 보여 드려요.");
        }

        PreviousComparison previous = previous(today, availableFrom, costByDay, minutesByDay, notes);

        BudgetStatus budgetStatus = null;
        if (budget != null) {
            BigDecimal budgetUsd = budget.toUsd(krwRate);
            BigDecimal rate = forecast == null ? null : forecast.budgetRate(budgetUsd);
            if (budgetUsd == null) {
                notes.add("환율을 가져오지 못해 원화 예산과 비교하지 않았어요.");
            }
            budgetStatus = new BudgetStatus(budget, budgetUsd, rate, thresholdReached(rate));
        }

        int running = (int) present.stream().filter(Ec2Instance::isRunning).count();
        int stopped = (int) present.stream().filter(i -> InstanceStates.isStopped(i.state())).count();
        return new DashboardView(accountId, today, YearMonth.from(today), availableFrom, monthToDate, running, stopped,
            forecast, budgetStatus, previous, freshness, List.copyOf(notes), asOf);
    }

    private static PreviousComparison previous(
        LocalDate today, LocalDate availableFrom, Map<LocalDate, BigDecimal> costByDay,
        Map<LocalDate, Long> minutesByDay, List<String> notes) {
        if (today.getDayOfMonth() == 1) {
            notes.add("이번 달 첫날이라 전월 같은 기간 비교는 내일부터 보여 드려요.");
            return null;
        }
        LocalDate yesterday = today.minusDays(1);
        LocalDate previousFrom = today.withDayOfMonth(1).minusMonths(1);
        LocalDate previousEnd = YearMonth.from(previousFrom).atEndOfMonth();
        LocalDate previousTo = previousFrom.plusDays(yesterday.getDayOfMonth() - 1L);
        if (previousTo.isAfter(previousEnd)) {
            previousTo = previousEnd;
        }
        if (availableFrom == null || availableFrom.isAfter(previousFrom)) {
            notes.add("전월 같은 기간(" + previousFrom + " ~ " + previousTo + ")은 기록 시작일 이전이 섞여 있어 비교하지 않아요.");
            return null;
        }
        PeriodTotal current = total(today.withDayOfMonth(1), yesterday, costByDay, minutesByDay);
        PeriodTotal previous = total(previousFrom, previousTo, costByDay, minutesByDay);
        BigDecimal change = current.costUsd().subtract(previous.costUsd());
        BigDecimal changeRate = previous.costUsd().signum() == 0 ? null
            : change.multiply(BigDecimal.valueOf(100)).divide(previous.costUsd(), 1, RoundingMode.HALF_UP);
        return new PreviousComparison(current, previous, change, changeRate);
    }

    private static PeriodTotal total(
        LocalDate from, LocalDate to, Map<LocalDate, BigDecimal> costByDay, Map<LocalDate, Long> minutesByDay) {
        BigDecimal cost = BigDecimal.ZERO;
        long minutes = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            cost = cost.add(costByDay.getOrDefault(d, BigDecimal.ZERO));
            minutes += minutesByDay.getOrDefault(d, 0L);
        }
        return new PeriodTotal(from, to, minutes, cost);
    }

    static Integer thresholdReached(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        Integer reached = null;
        for (int threshold : THRESHOLDS) {
            if (rate.compareTo(BigDecimal.valueOf(threshold)) >= 0) {
                reached = threshold;
            }
        }
        return reached;
    }

    /** 원화 예산 비교용 환율. 한 번도 받지 못했으면 null */
    private ExchangeRate krwRate() {
        return currencies.resolve(Currency.KRW).display().rate();
    }
}
