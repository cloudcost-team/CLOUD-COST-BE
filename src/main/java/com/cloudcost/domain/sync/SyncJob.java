package com.cloudcost.domain.sync;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * AWS 수집 1회 기록. 조회 시점에 캐시가 오래되어 AWS를 부를 때마다 하나 생긴다. CloudWatch 호출 수(cwRequestCount)를 남겨 고객 계정의 무료 한도 사용량을 추적한다.
 *
 * <p>결과: SUCCEEDED(전부 성공) / PARTIAL(일부만 저장 — 경고 또는 실패 원인이 남는다) / FAILED(아무것도 저장하지 못함).
 * PARTIAL의 실패 원인은 errorCode·errorMessage(한국어)에 남긴다. 성공한 부분은 이미 저장되어 있고,
 * 실패한 구간은 다음 수집에서 다시 읽는다.
 */
public final class SyncJob {

    public enum Status {
        RUNNING,
        SUCCEEDED,
        PARTIAL,
        FAILED
    }

    public enum Trigger {
        QUERY,
        MANUAL,
        /** 예약 수집 (ADR-019) */
        SCHEDULED
    }

    private final UUID id;
    private final String ownerId;
    private final UUID accountId;
    private final Trigger trigger;
    private final Instant startedAt;
    private Status status;
    private boolean instancesRefreshed;
    private boolean usageRefreshed;
    private int ec2RequestCount;
    private int cwRequestCount;
    private int instanceCount;
    private int usageRows;
    private String errorCode;
    private String errorMessage;
    private String warning;
    private Instant finishedAt;

    public SyncJob(
        UUID id,
        String ownerId,
        UUID accountId,
        Trigger trigger,
        Status status,
        boolean instancesRefreshed,
        boolean usageRefreshed,
        int ec2RequestCount,
        int cwRequestCount,
        int instanceCount,
        int usageRows,
        String errorCode,
        String errorMessage,
        String warning,
        Instant startedAt,
        Instant finishedAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.accountId = Objects.requireNonNull(accountId);
        this.trigger = Objects.requireNonNull(trigger);
        this.status = Objects.requireNonNull(status);
        this.instancesRefreshed = instancesRefreshed;
        this.usageRefreshed = usageRefreshed;
        this.ec2RequestCount = ec2RequestCount;
        this.cwRequestCount = cwRequestCount;
        this.instanceCount = instanceCount;
        this.usageRows = usageRows;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.warning = warning;
        this.startedAt = Objects.requireNonNull(startedAt);
        this.finishedAt = finishedAt;
    }

    public static SyncJob start(UUID id, String ownerId, UUID accountId, Trigger trigger, Instant now) {
        return new SyncJob(id, ownerId, accountId, trigger, Status.RUNNING, false, false, 0, 0, 0, 0, null, null, null,
            now, null);
    }

    public void instancesDone(int requests, int count) {
        this.ec2RequestCount += requests;
        this.instanceCount = count;
        this.instancesRefreshed = true;
    }

    public void addCloudWatchRequests(int requests, int rows) {
        this.cwRequestCount += requests;
        this.usageRows += rows;
    }

    public void usageDone() {
        this.usageRefreshed = true;
    }

    public void warn(String message) {
        this.warning = warning == null ? message : warning + " / " + message;
    }

    /** 일부가 실패했다 (성공한 부분은 저장됨). 첫 원인을 남기고 이후는 경고에 덧붙인다. */
    public void partialFailure(String code, String message) {
        if (errorCode == null) {
            this.errorCode = code;
            this.errorMessage = message;
        } else {
            warn(message);
        }
    }

    public void finish(Instant now) {
        this.status = warning == null && errorCode == null ? Status.SUCCEEDED : Status.PARTIAL;
        this.finishedAt = now;
    }

    public void fail(String code, String message, Instant now) {
        this.status = Status.FAILED;
        this.errorCode = code;
        this.errorMessage = message;
        this.finishedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String ownerId() {
        return ownerId;
    }

    public UUID accountId() {
        return accountId;
    }

    public Trigger trigger() {
        return trigger;
    }

    public Status status() {
        return status;
    }

    public boolean instancesRefreshed() {
        return instancesRefreshed;
    }

    public boolean usageRefreshed() {
        return usageRefreshed;
    }

    public int ec2RequestCount() {
        return ec2RequestCount;
    }

    public int cwRequestCount() {
        return cwRequestCount;
    }

    public int instanceCount() {
        return instanceCount;
    }

    public int usageRows() {
        return usageRows;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String warning() {
        return warning;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }
}
