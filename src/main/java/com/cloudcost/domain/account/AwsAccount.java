package com.cloudcost.domain.account;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * CloudCost Insight에 연결한 고객 AWS 계정 (연결 1건).
 *
 * <p>상태 흐름 (허용되지 않은 전이는 {@link IllegalStatusTransitionException}으로 막는다):
 * <pre>
 * PENDING      ─검증 성공→ ACTIVE    ─검증 실패→ ERROR
 * ACTIVE       ─수집 중 연결 오류(Role 거부·권한 없음·리전 비활성)→ ERROR   ─스택 삭제→ DISCONNECTED
 * ERROR        ─이후 수집 성공 또는 재검증 성공→ ACTIVE (복구)   ─스택 삭제(검증된 적 있을 때)→ DISCONNECTED
 * DISCONNECTED ─새 스택으로 실제 검증 성공→ ACTIVE / 검증 실패→ ERROR. 수집은 하지 않는다.
 * </pre>
 * 일시적인 수집 실패(요청 제한·AWS 장애·서버 설정)는 상태를 바꾸지 않고 lastError만 남긴다.
 * 자격증명은 이 객체에 절대 들어오지 않는다.
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
     * 한 번이라도 검증된 연결에 다른 AWS 계정의 Role을 붙이면 이전 계정 데이터와 섞이므로 막는다
     * (ACTIVE뿐 아니라 ERROR·DISCONNECTED가 된 연결도 마찬가지다).
     */
    public boolean wouldSwitchAwsAccount(RoleArn candidate) {
        return roleArn != null && verifiedAt != null && !roleArn.accountId().equals(candidate.accountId());
    }

    public void attachRole(RoleArn arn, Instant now) {
        this.roleArn = Objects.requireNonNull(arn);
        this.updatedAt = now;
    }

    /** 실제 AssumeRole + DescribeInstances 성공. 어떤 상태에서도 ACTIVE가 된다 (Role이 붙어 있어야 한다). */
    public void markVerified(Instant now) {
        if (roleArn == null) {
            throw new IllegalStatusTransitionException(status, Status.ACTIVE, "Role이 등록되지 않았습니다.");
        }
        this.status = Status.ACTIVE;
        this.verifiedAt = now;
        this.disconnectedAt = null;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.updatedAt = now;
    }

    /** 연결 검증(Role 등록·스택 신호) 실패. 지금 붙은 Role로는 접근할 수 없으므로 ERROR가 된다. */
    public void verificationFailed(String code, String message, Instant now) {
        this.status = Status.ERROR;
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.updatedAt = now;
    }

    /**
     * 수집 중 AWS 호출 실패.
     *
     * @param connectionBroken true면 사용자가 고쳐야 하는 오류(Role 거부·권한 없음·리전 비활성)라 ERROR가 된다.
     *                         false면 일시적인 오류라 상태는 그대로 두고 원인만 남긴다.
     */
    public void collectionFailed(String code, String message, boolean connectionBroken, Instant now) {
        requireCollectable(connectionBroken ? Status.ERROR : status);
        if (connectionBroken) {
            this.status = Status.ERROR;
        }
        this.lastErrorCode = code;
        this.lastErrorMessage = message;
        this.updatedAt = now;
    }

    /** 수집 전체 성공. 남은 오류를 지우고, ERROR였으면 ACTIVE로 복구한다. */
    public void collectionSucceeded(Instant now) {
        requireCollectable(Status.ACTIVE);
        if (status != Status.ACTIVE || lastErrorCode != null) {
            this.status = Status.ACTIVE;
            this.lastErrorCode = null;
            this.lastErrorMessage = null;
            this.updatedAt = now;
        }
    }

    /** 고객 계정에서 CloudCost Insight 스택(Role)을 만든 CloudFormation 스택 ARN. */
    public void linkStack(String stackId, Instant now) {
        this.stackId = stackId;
        this.updatedAt = now;
    }

    /**
     * 고객이 스택(Role)을 지웠다. 수집 기록은 남기고 더 이상 AWS를 부르지 않는다.
     * 이미 DISCONNECTED면 아무것도 하지 않는다 (신호가 중복으로 올 수 있다).
     */
    public void markDisconnected(Instant now) {
        if (status == Status.DISCONNECTED) {
            return;
        }
        if (!canDisconnect()) {
            throw new IllegalStatusTransitionException(status, Status.DISCONNECTED, "검증된 적이 없는 연결입니다.");
        }
        this.status = Status.DISCONNECTED;
        this.disconnectedAt = now;
        this.updatedAt = now;
    }

    /** 스택 삭제 신호로 끊을 수 있는지. 생성 실패로 롤백되는 삭제(검증된 적 없음)는 제외한다. */
    public boolean canDisconnect() {
        return verifiedAt != null && (status == Status.ACTIVE || status == Status.ERROR);
    }

    /** AWS 수집 대상인지: ACTIVE, 또는 한 번 검증된 뒤 오류가 난 ERROR (자동으로 다시 확인해 복구한다). */
    public boolean canCollect() {
        return status == Status.ACTIVE || (status == Status.ERROR && verifiedAt != null);
    }

    private void requireCollectable(Status target) {
        if (!canCollect()) {
            throw new IllegalStatusTransitionException(status, target, "수집 대상 연결이 아닙니다.");
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
