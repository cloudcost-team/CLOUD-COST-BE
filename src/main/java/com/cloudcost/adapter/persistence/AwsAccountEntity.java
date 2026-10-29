package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 연결 정보만 저장한다: owner, 리전, External ID, Role ARN, 상태, 시각. 자격증명 컬럼은 없다.
 */
@Entity
@Table(name = "aws_accounts")
class AwsAccountEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(nullable = false, length = 32)
    String region;

    @Column(length = 160)
    String alias;

    @Column(name = "external_id", nullable = false, length = 64)
    String externalId;

    @Column(name = "role_arn", length = 2048)
    String roleArn;

    @Column(name = "aws_account_id", length = 12)
    String awsAccountId;

    @Column(nullable = false, length = 16)
    String status;

    @Column(name = "last_error_code", length = 64)
    String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    String lastErrorMessage;

    @Column(name = "verified_at")
    Instant verifiedAt;

    @Column(name = "instances_refreshed_at")
    Instant instancesRefreshedAt;

    @Column(name = "usage_refreshed_at")
    Instant usageRefreshedAt;

    @Column(name = "stack_id", length = 512)
    String stackId;

    @Column(name = "disconnected_at")
    Instant disconnectedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected AwsAccountEntity() {
    }
}
