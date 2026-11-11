package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.AlertSettingsJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationDeliveryJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationJpa;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class NotificationPersistenceAdapter implements NotificationRepository, AlertSettingsRepository {

    /** V8의 UNIQUE (owner_id, event_key) 제약 이름 */
    static final String EVENT_KEY_CONSTRAINT = "uq_notifications_owner_event";

    private final NotificationJpa notifications;
    private final AlertSettingsJpa settings;
    private final NotificationDeliveryJpa deliveries;
    private final TransactionTemplate requiresNew;

    NotificationPersistenceAdapter(
        NotificationJpa notifications, AlertSettingsJpa settings, NotificationDeliveryJpa deliveries,
        PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.settings = settings;
        this.deliveries = deliveries;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AlertSettings> findByOwnerId(String ownerId) {
        return settings.findOneByOwnerId(ownerId)
            .map(e -> new AlertSettings(e.budget80, e.budget100, e.budget120, e.spike, e.weeklySummary));
    }

    @Override
    @Transactional
    public void save(String ownerId, AlertSettings s, Instant updatedAt) {
        AlertSettingsEntity e = settings.findOneByOwnerId(ownerId).orElseGet(AlertSettingsEntity::new);
        e.ownerId = ownerId;
        e.budget80 = s.budget80();
        e.budget100 = s.budget100();
        e.budget120 = s.budget120();
        e.spike = s.spike();
        e.weeklySummary = s.weeklySummary();
        e.updatedAt = updatedAt;
        settings.save(e);
    }

    /**
     * 알림마다 따로 커밋한다. 동시에 같은 알림을 만들려다 UNIQUE에 걸리면 이미 있는 것으로 본다.
     * 트랜잭션을 이 메서드 안({@link TransactionTemplate})에서 열고 닫아, 위반으로 롤백된 트랜잭션의 커밋 예외
     * (UnexpectedRollbackException)가 아니라 원래 위반을 catch에서 받는다.
     */
    @Override
    public boolean insertIfAbsent(Notification n) {
        if (notifications.existsByOwnerIdAndEventKey(n.ownerId(), n.eventKey())) {
            return false;
        }
        NotificationEntity e = new NotificationEntity();
        e.id = n.id();
        e.ownerId = n.ownerId();
        e.accountId = n.accountId();
        e.type = n.type().name();
        e.message = n.message();
        e.eventKey = n.eventKey();
        e.createdAt = n.createdAt();
        e.readAt = n.readAt();
        try {
            // 바로 flush해 UNIQUE 위반이 트랜잭션 안에서 나게 하고, 커밋까지 이 try 안에서 끝낸다
            requiresNew.executeWithoutResult(status -> notifications.saveAndFlush(e));
            return true;
        } catch (DataIntegrityViolationException e2) {
            if (isEventKeyDuplicate(e2)) {
                return false;
            }
            throw e2;
        }
    }

    /** 같은 (owner, event_key) UNIQUE 위반만 "이미 있음"으로 본다. 다른 무결성 위반(FK 등)은 그대로 던진다 */
    static boolean isEventKeyDuplicate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(EVENT_KEY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> findPage(String ownerId, Cursor cursor, int limit) {
        List<NotificationEntity> rows = cursor == null
            ? notifications.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, Limit.of(limit))
            : notifications.findPageAfter(ownerId, cursor.createdAt(), cursor.id(), Limit.of(limit));
        return rows.stream().map(NotificationPersistenceAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(String ownerId) {
        return notifications.countByOwnerIdAndReadAtIsNull(ownerId);
    }

    @Override
    @Transactional
    public int markAllRead(String ownerId, Instant readAt) {
        return notifications.markAllRead(ownerId, readAt);
    }

    @Override
    @Transactional
    public boolean markRead(String ownerId, UUID id, Instant readAt) {
        notifications.markRead(ownerId, id, readAt);
        return notifications.existsByOwnerIdAndId(ownerId, id);
    }

    @Override
    @Transactional
    public int deleteByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        // 발송 기록이 알림을 참조하므로 먼저 지운다 (ADR-019)
        deliveries.deleteByOwnerIdAndAccountId(ownerId, accountId);
        return notifications.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    private static Notification toDomain(NotificationEntity e) {
        return new Notification(e.id, e.ownerId, e.accountId, NotificationType.valueOf(e.type), e.message, e.eventKey,
            e.createdAt, e.readAt);
    }
}
