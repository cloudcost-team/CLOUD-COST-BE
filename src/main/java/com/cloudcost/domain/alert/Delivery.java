package com.cloudcost.domain.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 알림 1건을 채널 1개로 보낸 기록 (Outbox의 발송 상태). 실패하면 간격을 늘려 다시 보내고,
 * {@link #MAX_ATTEMPTS}번 실패하거나 다시 보내도 소용없는 실패(웹훅 삭제 등)면 FAILED로 멈춘다.
 * 알림 자체는 서비스 안 알림창에 그대로 남는다.
 */
public record Delivery(
    UUID id,
    String ownerId,
    UUID notificationId,
    UUID channelId,
    Status status,
    int attempts,
    String lastError,
    Instant nextAttemptAt,
    Instant sentAt,
    Instant createdAt) {

    public enum Status {
        PENDING("보내는 중"),
        SENT("보냄"),
        FAILED("보내지 못함");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final int MAX_ATTEMPTS = 5;

    /** n번째 실패 뒤 기다릴 시간 */
    static final List<Duration> BACKOFF = List.of(
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));

    public static Delivery pending(UUID id, String ownerId, UUID notificationId, UUID channelId, Instant now) {
        return new Delivery(id, ownerId, notificationId, channelId, Status.PENDING, 0, null, now, null, now);
    }

    public Delivery sent(Instant now) {
        return new Delivery(id, ownerId, notificationId, channelId, Status.SENT, attempts + 1, null, now, now,
            createdAt);
    }

    /** @param retryable 다시 보내면 될 수도 있는 실패인지 (요청 제한·일시 오류) */
    public Delivery failed(String reason, boolean retryable, Instant now) {
        int tried = attempts + 1;
        if (!retryable || tried >= MAX_ATTEMPTS) {
            return new Delivery(id, ownerId, notificationId, channelId, Status.FAILED, tried, reason, now, null,
                createdAt);
        }
        Duration wait = BACKOFF.get(Math.min(tried, BACKOFF.size()) - 1);
        return new Delivery(id, ownerId, notificationId, channelId, Status.PENDING, tried, reason, now.plus(wait),
            null, createdAt);
    }
}
