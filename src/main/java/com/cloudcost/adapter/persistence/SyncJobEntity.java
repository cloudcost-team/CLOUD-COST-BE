package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_jobs")
class SyncJobEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(name = "trigger_type", nullable = false, length = 16)
    String triggerType;

    @Column(nullable = false, length = 16)
    String status;

    @Column(name = "instances_refreshed", nullable = false)
    boolean instancesRefreshed;

    @Column(name = "usage_refreshed", nullable = false)
    boolean usageRefreshed;

    @Column(name = "ec2_request_count", nullable = false)
    int ec2RequestCount;

    @Column(name = "cw_request_count", nullable = false)
    int cwRequestCount;

    @Column(name = "instance_count", nullable = false)
    int instanceCount;

    @Column(name = "usage_rows", nullable = false)
    int usageRows;

    @Column(name = "error_code", length = 64)
    String errorCode;

    @Column(name = "error_message", length = 1000)
    String errorMessage;

    @Column(length = 2000)
    String warning;

    @Column(name = "started_at", nullable = false)
    Instant startedAt;

    @Column(name = "finished_at")
    Instant finishedAt;

    protected SyncJobEntity() {
    }
}
