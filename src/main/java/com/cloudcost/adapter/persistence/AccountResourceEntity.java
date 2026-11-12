package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_resources")
class AccountResourceEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(nullable = false, length = 20)
    String type;

    @Column(name = "resource_id", nullable = false, length = 255)
    String resourceId;

    @Column(length = 255)
    String name;

    @Column(nullable = false, length = 32)
    String region;

    @Column(length = 64)
    String subtype;

    @Column(name = "size_gb")
    Integer sizeGb;

    @Column(name = "in_use", nullable = false)
    boolean inUse;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "request_count")
    Long requestCount;

    @Column(length = 100)
    String platform;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    protected AccountResourceEntity() {
    }
}
