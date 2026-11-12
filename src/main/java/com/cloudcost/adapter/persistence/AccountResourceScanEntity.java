package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_resource_scans")
class AccountResourceScanEntity {

    @Id
    @Column(name = "account_id")
    UUID accountId;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(nullable = false, length = 32)
    String status;

    @Column(name = "permission_version", nullable = false)
    int permissionVersion;

    @Column(name = "scanned_at")
    Instant scannedAt;

    @Column(length = 500)
    String message;

    @Column(name = "describe_count", nullable = false)
    int describeCount;

    @Column(name = "cw_request_count", nullable = false)
    int cwRequestCount;

    protected AccountResourceScanEntity() {
    }
}
