package com.cloudcost.application;

import com.cloudcost.application.DashboardService.DashboardView;
import com.cloudcost.application.Ec2UsageQueryService.UsageCostView;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.ConflictException;
import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.application.port.NotificationRepository.Cursor;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.alert.AlertRules;
import com.cloudcost.domain.alert.AlertRules.Candidate;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.CostEstimator.PeriodCost;
import com.cloudcost.domain.cost.DisplayCurrency;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 서비스 안 알림 (이슈 #35, ADR-014). 예약 수집 없이, 사용자가 알림 목록·안 읽은 수를 조회할 때 연결마다 조건을 평가하고
 * 새로 생긴 일만 알림으로 저장한다 (발생 키로 한 번만). 외부(Slack·이메일·푸시)로 보내지 않는다.
 */
public class NotificationService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    /** 연결별 예산 (S14 목록). budget이 null이면 설정 안 함 */
    public record BudgetRow(AwsAccount account, String name, MonthlyBudget budget) {
    }

    public record SettingsView(AlertSettings settings, List<BudgetRow> budgets) {
    }

    /** S14 저장: 예산 amount가 null이면 그 연결의 예산을 지운다 */
    public record BudgetChange(UUID accountId, MonthlyBudget budget) {
    }

    /**
     * @param accountNames accountId → 연결 이름 (지워진 연결은 없다)
     * @param nextCursor   더 있으면 다음 페이지 위치, 없으면 null
     */
    public record Page(List<Notification> items, Map<UUID, String> accountNames, String nextCursor, long unreadCount) {
    }

    private final AwsAccountService accounts;
    private final DashboardService dashboards;
    private final Ec2UsageQueryService queries;
    private final BudgetRepository budgets;
    private final AlertSettingsRepository settings;
    private final NotificationRepository notifications;
    private final CurrencyService currencies;
    private final Clock clock;
    private final UsageSettings usage;

    public NotificationService(
        AwsAccountService accounts,
        DashboardService dashboards,
        Ec2UsageQueryService queries,
        BudgetRepository budgets,
        AlertSettingsRepository settings,
        NotificationRepository notifications,
        CurrencyService currencies,
        Clock clock,
        UsageSettings usage) {
        this.accounts = accounts;
        this.dashboards = dashboards;
        this.queries = queries;
        this.budgets = budgets;
        this.settings = settings;
        this.notifications = notifications;
        this.currencies = currencies;
        this.clock = clock;
        this.usage = usage;
    }

    public SettingsView settings(String ownerId) {
        AlertSettings current = settings.findByOwnerId(ownerId).orElse(AlertSettings.defaults());
        List<BudgetRow> rows = accounts.list(ownerId, false).stream()
            .map(a -> new BudgetRow(a, connectionName(a), budgets.findByOwnerIdAndAccountId(ownerId, a.id()).orElse(null)))
            .toList();
        return new SettingsView(current, rows);
    }

    /**
     * 조건과 연결별 예산을 한 번에 저장한다. 남의 연결이 하나라도 있으면 아무것도 바꾸지 않고 404.
     */
    public SettingsView updateSettings(String ownerId, AlertSettings conditions, List<BudgetChange> changes) {
        List<BudgetChange> list = changes == null ? List.of() : changes;
        list.forEach(c -> accounts.get(ownerId, c.accountId()));
        settings.save(ownerId, conditions, clock.instant());
        for (BudgetChange c : list) {
            if (c.budget() == null) {
                dashboards.deleteBudget(ownerId, c.accountId());
            } else {
                dashboards.setBudget(ownerId, c.accountId(), c.budget());
            }
        }
        return settings(ownerId);
    }

    public Page list(String ownerId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new DomainValidationException("VALIDATION_ERROR", "limit은 1~" + MAX_LIMIT + " 사이로 입력해 주세요.");
        }
        Cursor position = NotificationCursor.decode(cursor);
        evaluate(ownerId);
        List<Notification> rows = notifications.findPage(ownerId, position, size + 1);
        boolean more = rows.size() > size;
        List<Notification> items = more ? rows.subList(0, size) : rows;
        Cursor next = more ? new Cursor(items.getLast().createdAt(), items.getLast().id()) : null;
        Map<UUID, String> names = new LinkedHashMap<>();
        accounts.list(ownerId, true).forEach(a -> names.put(a.id(), connectionName(a)));
        return new Page(List.copyOf(items), names, NotificationCursor.encode(next), notifications.countUnread(ownerId));
    }

    public long unreadCount(String ownerId) {
        evaluate(ownerId);
        return notifications.countUnread(ownerId);
    }

    public long readAll(String ownerId) {
        notifications.markAllRead(ownerId, clock.instant());
        return notifications.countUnread(ownerId);
    }

    public void read(String ownerId, UUID id) {
        if (!notifications.markRead(ownerId, id, clock.instant())) {
            throw new NotFoundException("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.");
        }
    }

    /**
     * 연결마다 예산·급증·주간 요약 조건을 평가해 새로 생긴 알림을 저장한다.
     * 수집할 수 없는 연결(대기·끊김)이나 AWS 오류가 난 연결은 이번에는 건너뛴다 (알림 목록 조회를 막지 않는다).
     *
     * @return 새로 만든 알림 수
     */
    int evaluate(String ownerId) {
        AlertSettings current = settings.findByOwnerId(ownerId).orElse(AlertSettings.defaults());
        int created = 0;
        for (AwsAccount account : accounts.list(ownerId, false)) {
            if (!account.canCollect()) {
                continue;
            }
            try {
                for (Candidate c : candidates(ownerId, account, current)) {
                    if (notifications.insertIfAbsent(new Notification(UUID.randomUUID(), ownerId, c.accountId(),
                        c.type(), c.message(), c.eventKey(), clock.instant(), null))) {
                        created++;
                    }
                }
            } catch (ConflictException | NotFoundException | AwsAccessException e) {
                // 이 연결은 다음 조회 때 다시 평가한다
            }
        }
        return created;
    }

    private List<Candidate> candidates(String ownerId, AwsAccount account, AlertSettings current) {
        List<Candidate> result = new ArrayList<>();
        if (!current.budget80() && !current.budget100() && !current.budget120() && !current.spike()
            && !current.weeklySummary()) {
            return result;
        }
        String name = connectionName(account);
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
        for (PeriodCost<LocalDate> p : daily.report().periods()) {
            BigDecimal cost = p.estimatedCost() == null ? BigDecimal.ZERO : p.estimatedCost();
            if (p.period().isBefore(lastMonday)) {
                previousWeek = previousWeek.add(cost);
            } else {
                lastWeek = lastWeek.add(cost);
            }
        }
        BigDecimal previous = availableFrom.isAfter(previousMonday) ? null : previousWeek;
        DisplayCurrency display = currencies.resolve(Currency.KRW).display();
        return AlertRules.weeklySummary(account.id(), lastMonday, lastWeek, previous, display, current);
    }

    /** 연결 이름 없을 때 (기능명세서 F02 규칙과 같은 문구) */
    public static final String UNNAMED = "이름 없는 연결";

    /** 사용자가 붙인 이름, 없으면 "이름 없는 연결" */
    static String connectionName(AwsAccount account) {
        if (account.alias() != null && !account.alias().isBlank()) {
            return account.alias();
        }
        return UNNAMED;
    }
}
