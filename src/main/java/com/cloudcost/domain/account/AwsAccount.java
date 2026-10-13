package com.cloudcost.domain.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * CloudCost Insight에 연결한 고객 AWS 계정 (연결 1건).
 *
 * <p>상태 흐름: PENDING(External ID 발급) → Role 생성 신호 또는 Role ARN 등록 + 실제 AssumeRole 검증 → ACTIVE / ERROR.
 * 고객이 CloudFormation 스택을 지우면 DISCONNECTED. 자격증명은 이 객체에 절대 들어오지 않는다.
 */
public final class AwsAccount {

    public enum Status {
        PENDING,
        ACTIVE,
        ERROR,
        DISCONNECTED
    }

    private final UUID id;
    private final String ownerId;
    private final AwsRegion region;
    private final ExternalId externalId;
    private final Instant createdAt;
    private RoleArn roleArn;
    private Status status;
    private String lastErrorCode;
    private String lastErrorMessage;
    private Instant verifiedAt;
    private Instant instancesRefreshedAt;
    private Instant usageRefreshedAt;
    private String stackId;
    private Instant disconnectedAt;
    private Instant updatedAt;

    public AwsAccount(
        UUID id,
        String ownerId,
        AwsRegion region,
        ExternalId externalId,
        RoleArn roleArn,
        Status status,
        String lastErrorCode,
        String lastErrorMessage,
        Instant verifiedAt,
        Instant instancesRefreshedAt,
        Instant usageRefreshedAt,
        String stackId,
        Instant disconnectedAt,
        Instant createdAt,
        Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.region = Objects.requireNonNull(region);
        this.externalId = Objects.requireNonNull(externalId);
        this.roleArn = roleArn;
        this.status = Objects.requireNonNull(status);
        this.lastErrorCode = lastErrorCode;
        this.lastErrorMessage = lastErrorMessage;
        this.verifiedAt = verifiedAt;
        this.instancesRefreshedAt = instancesRefreshedAt;
        this.usageRefreshedAt = usageRefreshedAt;
        this.stackId = stackId;
        this.disconnectedAt = disconnectedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    public static AwsAccount createPending(UUID id, String ownerId, AwsRegion region, Instant now) {
        return new AwsAccount(
            id, ownerId, region, ExternalId.generate(), null, Status.PENDING, null, null, null,
            null, null, null, null, now, now);
    }

    /**
     * ACTIVE였던 연결에 다른 AWS 계정의 Role을 붙이면 이전 계정 데이터와 섞이므로 막는다.
     */
    public boolean wouldSwitchAwsAccount(RoleArn candidate) {
        return roleArn != null && status == Status.ACTIVE && !roleArn.accountId().equals(candidate.accountId());
    }

    public void attachRole(RoleArn arn, Instant now) {
        this.roleArn = Objects.requireNonNull(arn);
        this.updatedAt = now;
    }

    public void markVerified(Instant now) {
        this.status = Status.ACTIVE;
        this.verifiedAt = now;
        this.disconnectedAt = null;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.updatedAt = now;
    }

    /**
     * 검증 실패. ACTIVE 연결이 이후 조회에서 실패한 경우에는 상태를 유지하고 오류만 기록한다.
     */
    public void recordError(String code, String message, Instant now) {
        if (status != Status.ACTIVE) {
            this.status = Status.ERROR;
        }
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.updatedAt = now;
    }

    /** 고객 계정에서 CloudCost Insight 스택(Role)을 만든 CloudFormation 스택 ARN. */
    public void linkStack(String stackId, Instant now) {
        this.stackId = stackId;
        this.updatedAt = now;
    }

    /** 고객이 스택(Role)을 지웠다. 수집 기록은 남기고 더 이상 AWS를 부르지 않는다. */
    public void markDisconnected(Instant now) {
        this.status = Status.DISCONNECTED;
        this.disconnectedAt = now;
        this.updatedAt = now;
    }

    public void clearError(Instant now) {
        if (lastErrorCode != null) {
            this.lastErrorCode = null;
            this.lastErrorMessage = null;
            this.updatedAt = now;
        }
    }

    public void instancesRefreshed(Instant now) {
        this.instancesRefreshedAt = now;
        this.updatedAt = now;
    }

    /**
     * CloudWatch를 호출한 시각(성공·실패 무관). 1시간 1회 제한의 기준이다.
     */
    public void usageRefreshed(Instant now) {
        this.usageRefreshedAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public AwsRegion region() {
        return region;
    }

    public ExternalId externalId() {
        return externalId;
    }

    public RoleArn roleArn() {
        return roleArn;
    }

    public Status status() {
        return status;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public String lastErrorMessage() {
        return lastErrorMessage;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    public Instant instancesRefreshedAt() {
        return instancesRefreshedAt;
    }

    public Instant usageRefreshedAt() {
        return usageRefreshedAt;
    }

    public String stackId() {
        return stackId;
    }

    public Instant disconnectedAt() {
        return disconnectedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
