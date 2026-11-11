package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationChannelJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationDeliveryJpa;
import com.cloudcost.application.port.NotificationChannelRepository;
import com.cloudcost.application.port.NotificationDeliveryRepository;
import com.cloudcost.domain.alert.ChannelType;
import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationChannel;
import com.cloudcost.domain.alert.NotificationType;
import java.time.Instant;
import java.util.Collection;
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

/** 외부 알림 채널과 발송 기록 (ADR-019) */
@Component
class ChannelPersistenceAdapter implements NotificationChannelRepository, NotificationDeliveryRepository {

    /** V10의 UNIQUE (notification_id, channel_id) 제약 이름 */
    static final String DELIVERY_CONSTRAINT = "uq_notification_deliveries";

    private final NotificationChannelJpa channels;
    private final NotificationDeliveryJpa deliveries;
    private final TransactionTemplate requiresNew;

    ChannelPersistenceAdapter(
        NotificationChannelJpa channels, NotificationDeliveryJpa deliveries,
        PlatformTransactionManager transactionManager) {
        this.channels = channels;
        this.deliveries = deliveries;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public NotificationChannel save(NotificationChannel c) {
        NotificationChannelEntity e = channels.findByOwnerIdAndId(c.ownerId(), c.id())
            .orElseGet(NotificationChannelEntity::new);
        e.id = c.id();
        e.ownerId = c.ownerId();
        e.type = c.type().name();
        e.webhookUrl = c.webhookUrl();
        e.enabled = c.enabled();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        return toDomain(channels.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationChannel> findByOwnerIdAndId(String ownerId, UUID id) {
        return channels.findByOwnerIdAndId(ownerId, id).map(ChannelPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationChannel> findAllByOwnerId(String ownerId) {
        return channels.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
            .map(ChannelPersistenceAdapter::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public boolean deleteByOwnerIdAndId(String ownerId, UUID id) {
        deliveries.deleteByOwnerIdAndChannelId(ownerId, id);
        return channels.deleteByOwnerIdAndId(ownerId, id) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Undelivered> findUndelivered(Instant since, int limit) {
        return deliveries.findUndelivered(since, Limit.of(limit)).stream()
            .map(row -> new Undelivered((String) row[0], (UUID) row[1], (UUID) row[2]))
            .toList();
    }

    /** 따로 커밋한다. 동시에 같은 쌍을 만들려다 UNIQUE에 걸리면 이미 있는 것으로 본다 (알림 저장과 같은 방식) */
    @Override
    public boolean insertIfAbsent(Delivery d) {
        if (deliveries.existsByNotificationIdAndChannelId(d.notificationId(), d.channelId())) {
            return false;
        }
        NotificationDeliveryEntity e = toEntity(d);
        try {
            requiresNew.executeWithoutResult(status -> deliveries.saveAndFlush(e));
            return true;
        } catch (DataIntegrityViolationException ex) {
            for (Throwable t = ex; t != null; t = t.getCause()) {
                String message = t.getMessage();
                if (message != null && message.toLowerCase(Locale.ROOT).contains(DELIVERY_CONSTRAINT)) {
                    return false;
                }
            }
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Due> findDue(Instant now, int limit) {
        return deliveries.findDue(now, Limit.of(limit)).stream()
            .map(row -> new Due(
                toDomain((NotificationDeliveryEntity) row[0]),
                toDomain((NotificationEntity) row[1]),
                toDomain((NotificationChannelEntity) row[2]),
                row[3] == null || ((String) row[3]).isBlank() ? null : (String) row[3]))
            .toList();
    }

    @Override
    @Transactional
    public void save(Delivery d) {
        deliveries.save(toEntity(d));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Delivery> findByOwnerIdAndNotificationIds(String ownerId, Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return List.of();
        }
        return deliveries.findByOwnerIdAndNotificationIdIn(ownerId, notificationIds).stream()
            .map(ChannelPersistenceAdapter::toDomain)
            .toList();
    }

    private static NotificationDeliveryEntity toEntity(Delivery d) {
        NotificationDeliveryEntity e = new NotificationDeliveryEntity();
        e.id = d.id();
        e.ownerId = d.ownerId();
        e.notificationId = d.notificationId();
        e.channelId = d.channelId();
        e.status = d.status().name();
        e.attempts = d.attempts();
        e.lastError = d.lastError() == null ? null : truncate(d.lastError(), 300);
        e.nextAttemptAt = d.nextAttemptAt();
        e.sentAt = d.sentAt();
        e.createdAt = d.createdAt();
        return e;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Delivery toDomain(NotificationDeliveryEntity e) {
        return new Delivery(e.id, e.ownerId, e.notificationId, e.channelId, Delivery.Status.valueOf(e.status),
            e.attempts, e.lastError, e.nextAttemptAt, e.sentAt, e.createdAt);
    }

    private static NotificationChannel toDomain(NotificationChannelEntity e) {
        return new NotificationChannel(e.id, e.ownerId, ChannelType.valueOf(e.type), e.webhookUrl, e.enabled,
            e.createdAt, e.updatedAt);
    }

    private static Notification toDomain(NotificationEntity e) {
        return new Notification(e.id, e.ownerId, e.accountId, NotificationType.valueOf(e.type), e.message, e.eventKey,
            e.createdAt, e.readAt);
    }
}
