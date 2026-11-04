package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class NotificationEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(nullable = false, length = 20)
    String type;

    @Column(nullable = false, length = 500)
    String message;

    @Column(name = "event_key", nullable = false, length = 200)
    String eventKey;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "read_at")
    Instant readAt;

    protected NotificationEntity() {
    }
}
