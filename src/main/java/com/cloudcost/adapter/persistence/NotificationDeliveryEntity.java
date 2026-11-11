package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
class NotificationDeliveryEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "notification_id", nullable = false)
    UUID notificationId;

    @Column(name = "channel_id", nullable = false)
    UUID channelId;

    @Column(nullable = false, length = 16)
    String status;

    @Column(nullable = false)
    int attempts;

    @Column(name = "last_error", length = 300)
    String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    Instant nextAttemptAt;

    @Column(name = "sent_at")
    Instant sentAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected NotificationDeliveryEntity() {
    }
}
