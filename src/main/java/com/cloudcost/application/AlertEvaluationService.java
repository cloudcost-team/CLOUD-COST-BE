package com.cloudcost.application;

import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.ConflictException;
import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.alert.AlertRules;
import com.cloudcost.domain.alert.AlertRules.Candidate;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 알림 조건 평가 (ADR-014, ADR-019). 연결마다 예산·급증·주간 요약 조건을 확인해 새로 생긴 일만 알림으로 저장한다
 * (발생 키로 한 번만). 사용자가 알림을 조회할 때와 예약 수집이 연결을 수집한 뒤에 부른다.
 * 외부 채널 발송은 {@link NotificationDispatchService}가 따로 한다 (평가와 발송 분리).
 */
public class AlertEvaluationService {

    private final AwsAccountService accounts;
    private final DashboardService dashboards;
    private final Ec2UsageQueryService queries;
    private final AlertSettingsRepository settings;
    private final NotificationRepository notifications;
    private final CurrencyService currencies;
    private final Clock clock;
    private final UsageSettings usage;

    public AlertEvaluationService(
        AwsAccountService accounts,
        DashboardService dashboards,
        Ec2UsageQueryService queries,
        AlertSettingsRepository settings,
        NotificationRepository notifications,
        CurrencyService currencies,
        Clock clock,
        UsageSettings usage) {
        this.accounts = accounts;
        this.dashboards = dashboards;
        this.queries = queries;
        this.settings = settings;
        this.notifications = notifications;
        this.currencies = currencies;
        this.clock = clock;
        this.usage = usage;
    }

    /**
     * 연결마다 예산·급증·주간 요약 조건을 평가해 새로 생긴 알림을 저장한다.
     * 수집할 수 없는 연결(대기·끊김)이나 AWS 오류가 난 연결은 이번에는 건너뛴다 (알림 목록 조회를 막지 않는다).
     *
     * @return 새로 만든 알림 수
     */
    public int evaluate(String ownerId) {
        AlertSettings current = settings.findByOwnerId(ownerId).orElse(AlertSettings.defaults());
        int created = 0;
        for (AwsAccount account : accounts.list(ownerId, false)) {
            created += evaluate(ownerId, account, current);
        }
        return created;
    }

    /** 연결 하나만 평가한다 (예약 수집이 그 연결을 수집한 뒤). */
    public int evaluate(String ownerId, AwsAccount account) {
        return evaluate(ownerId, account, settings.findByOwnerId(ownerId).orElse(AlertSettings.defaults()));
    }

    private int evaluate(String ownerId, AwsAccount account, AlertSettings current) {
        if (!account.canCollect()) {
            return 0;
        }
        int created = 0;
        try {
            for (Candidate c : candidates(ownerId, account, current)) {
                if (notifications.insertIfAbsent(new Notification(UUID.randomUUID(), ownerId, c.accountId(),
                    c.type(), c.message(), c.eventKey(), clock.instant(), null))) {
                    created++;
                }
            }
        } catch (ConflictException | NotFoundException | AwsAccessException e) {
            // 이 연결은 다음 조회·예약 수집 때 다시 평가한다
        }
        return created;
    }

    private List<Candidate> candidates(String ownerId, AwsAccount account, AlertSettings current) {
        List<Candidate> result = new ArrayList<>();
        if (!current.budget80() && !current.budget100() && !current.budget120() && !current.spike()
            && !current.weeklySummary()) {
            return result;
        }
        String name = NotificationService.connectionName(account);
        if (current.budget80() || current.budget100() || current.budget120() || current.spike()) {
            DashboardView dashboard = dashboards.dashboard(ownerId, account.id());
            if (dashboard.budget() != null) {
                AlertRules.budget(account.id(), name, dashboard.month(), dashboard.budget().thresholdReached(), current)
                    .ifPresent(result::add);
            }
            if (dashboard.latestSpike() != null) {
                AlertRules.spike(account.id(), dashboard.latestSpike().spike(), dashboard.latestSpike().causeName(),
                    current).ifPresent(result::add);
            }
        }
        if (current.weeklySummary()) {
            weeklySummary(ownerId, account, current).ifPresent(result::add);
        }
        return result;
    }

    /**
     * 지난주(월~일)가 모두 기록 시작일 이후일 때만 요약한다. 전주가 온전하지 않으면 비교 문구를 빼고 보낸다.
     */
    private Optional<Candidate> weeklySummary(String ownerId, AwsAccount account, AlertSettings current) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), usage.zone());
        LocalDate lastMonday = AlertRules.lastWeekMonday(today);
        LocalDate previousMonday = lastMonday.minusWeeks(1);
        UsageCostView<LocalDate> daily = queries.daily(ownerId, account.id(), previousMonday, lastMonday.plusDays(6));
        LocalDate availableFrom = daily.availableFrom();
        if (availableFrom == null || availableFrom.isAfter(lastMonday) || daily.report().totalEstimatedCost() == null) {
            return Optional.empty();
        }
        BigDecimal lastWeek = BigDecimal.ZERO;
        BigDecimal previousWeek = BigDecimal.ZERO;
        // 쓴 날은 있는데 단가를 아는 날이 하나도 없는 주는 0원이 아니라 "모름"이다
        boolean lastWeekUsed = false;
        boolean lastWeekPriced = false;
        boolean previousWeekUsed = false;
        boolean previousWeekPriced = false;
        for (PeriodCost<LocalDate> p : daily.report().periods()) {
            boolean previousWeekDay = p.period().isBefore(lastMonday);
            boolean used = p.usedMinutes() > 0;
            boolean priced = p.estimatedCost() != null;
            BigDecimal cost = priced ? p.estimatedCost() : BigDecimal.ZERO;
            if (previousWeekDay) {
                previousWeek = previousWeek.add(cost);
                previousWeekUsed |= used;
                previousWeekPriced |= used && priced;
            } else {
                lastWeek = lastWeek.add(cost);
                lastWeekUsed |= used;
                lastWeekPriced |= used && priced;
            }
        }
        if (lastWeekUsed && !lastWeekPriced) {
            return Optional.empty();
        }
        BigDecimal previous = availableFrom.isAfter(previousMonday) || (previousWeekUsed && !previousWeekPriced)
            ? null : previousWeek;
        DisplayCurrency display = currencies.resolve(Currency.KRW).display();
        return AlertRules.weeklySummary(account.id(), lastMonday, lastWeek, previous, display, current);
    }
}
