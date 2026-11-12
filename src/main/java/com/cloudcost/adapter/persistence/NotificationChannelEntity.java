package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_channels")
class NotificationChannelEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(nullable = false, length = 20)
    String type;

    @Column(name = "webhook_url", nullable = false, length = 600)
    String webhookUrl;

    @Column(nullable = false)
    boolean enabled;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected NotificationChannelEntity() {
    }
}
