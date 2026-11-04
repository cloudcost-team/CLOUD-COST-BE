package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.AlertSettingsJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationJpa;
import com.cloudcost.application.port.AlertSettingsRepository;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.domain.alert.AlertSettings;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class NotificationPersistenceAdapter implements NotificationRepository, AlertSettingsRepository {

    private final NotificationJpa notifications;
    private final AlertSettingsJpa settings;

    NotificationPersistenceAdapter(NotificationJpa notifications, AlertSettingsJpa settings) {
        this.notifications = notifications;
        this.settings = settings;
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
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
            notifications.save(e);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
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
        return notifications.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    private static Notification toDomain(NotificationEntity e) {
        return new Notification(e.id, e.ownerId, e.accountId, NotificationType.valueOf(e.type), e.message, e.eventKey,
            e.createdAt, e.readAt);
    }
}
