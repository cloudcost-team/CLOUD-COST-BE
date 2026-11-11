package com.cloudcost.application;

import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.application.port.NotificationChannelRepository;
import com.cloudcost.application.port.NotificationDeliveryRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.application.port.NotificationRepository.Cursor;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.NotificationChannel;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.budget.MonthlyBudget;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 서비스 안 알림 (이슈 #35, ADR-014). 사용자가 알림 목록·안 읽은 수를 조회할 때도 조건을 평가한다
 * ({@link AlertEvaluationService}). 예약 수집이 켜져 있으면 접속하지 않아도 평가되고,
 * 등록한 외부 채널(Discord)로도 보낸다 (ADR-019, {@link NotificationDispatchService}).
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
    public record Page(
        List<Notification> items,
        Map<UUID, String> accountNames,
        String nextCursor,
        long unreadCount,
        Map<UUID, List<DeliveryView>> deliveries) {
    }

    /** 알림 1건의 외부 채널 발송 상태 (채널이 지워졌으면 빠진다) */
    public record DeliveryView(Delivery delivery, NotificationChannel channel) {
    }

    private final AwsAccountService accounts;
    private final DashboardService dashboards;
    private final BudgetRepository budgets;
    private final AlertSettingsRepository settings;
    private final NotificationRepository notifications;
    private final NotificationChannelRepository channels;
    private final NotificationDeliveryRepository deliveries;
    private final AlertEvaluationService alerts;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public NotificationService(
        AwsAccountService accounts,
        DashboardService dashboards,
        BudgetRepository budgets,
        AlertSettingsRepository settings,
        NotificationRepository notifications,
        NotificationChannelRepository channels,
        NotificationDeliveryRepository deliveries,
        AlertEvaluationService alerts,
        UnitOfWork unitOfWork,
        Clock clock) {
        this.accounts = accounts;
        this.dashboards = dashboards;
        this.budgets = budgets;
        this.settings = settings;
        this.notifications = notifications;
        this.channels = channels;
        this.deliveries = deliveries;
        this.alerts = alerts;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
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
        // 도중에 실패하면(예: 확인 뒤 연결이 지워짐) 조건·앞선 예산까지 모두 되돌린다
        unitOfWork.run(() -> {
            settings.save(ownerId, conditions, clock.instant());
            for (BudgetChange c : list) {
                if (c.budget() == null) {
                    dashboards.deleteBudget(ownerId, c.accountId());
                } else {
                    dashboards.setBudget(ownerId, c.accountId(), c.budget());
                }
            }
        });
        return settings(ownerId);
    }

    public Page list(String ownerId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new DomainValidationException("VALIDATION_ERROR", "limit은 1~" + MAX_LIMIT + " 사이로 입력해 주세요.");
        }
        Cursor position = NotificationCursor.decode(cursor);
        alerts.evaluate(ownerId);
        List<Notification> rows = notifications.findPage(ownerId, position, size + 1);
        boolean more = rows.size() > size;
        List<Notification> items = more ? rows.subList(0, size) : rows;
        Cursor next = more ? new Cursor(items.getLast().createdAt(), items.getLast().id()) : null;
        Map<UUID, String> names = new LinkedHashMap<>();
        accounts.list(ownerId, true).forEach(a -> names.put(a.id(), connectionName(a)));
        return new Page(List.copyOf(items), names, NotificationCursor.encode(next), notifications.countUnread(ownerId),
            deliveryViews(ownerId, items));
    }

    private Map<UUID, List<DeliveryView>> deliveryViews(String ownerId, List<Notification> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        Map<UUID, NotificationChannel> byId = channels.findAllByOwnerId(ownerId).stream()
            .collect(Collectors.toMap(NotificationChannel::id, Function.identity()));
        return deliveries.findByOwnerIdAndNotificationIds(ownerId, items.stream().map(Notification::id).toList())
            .stream()
            .filter(d -> byId.containsKey(d.channelId()))
            .collect(Collectors.groupingBy(Delivery::notificationId,
                Collectors.mapping(d -> new DeliveryView(d, byId.get(d.channelId())), Collectors.toList())));
    }

    public long unreadCount(String ownerId) {
        alerts.evaluate(ownerId);
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
