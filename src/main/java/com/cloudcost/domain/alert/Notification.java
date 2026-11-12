package com.cloudcost.domain.alert;

import java.time.Instant;
import java.util.UUID;

/**
 * 서비스 안 알림 1건. 같은 일은 eventKey로 한 번만 만든다 (owner별 UNIQUE).
 *
 * @param message 만들 때 정한 한국어 문구 (금액은 그때의 통화·환율로 적는다)
 * @param readAt  읽지 않았으면 null
 * @param facts   만들 때의 숫자 (외부 채널 메시지용). null이면 {@link AlertFacts#NONE}
 */
public record Notification(
    UUID id,
    String ownerId,
    UUID accountId,
    NotificationType type,
    String message,
    String eventKey,
    Instant createdAt,
    Instant readAt,
    AlertFacts facts) {

    public Notification {
        facts = facts == null ? AlertFacts.NONE : facts;
    }

    /** 숫자 없이 만든다 (기존 알림·테스트) */
    public Notification(
        UUID id, String ownerId, UUID accountId, NotificationType type, String message, String eventKey,
        Instant createdAt, Instant readAt) {
        this(id, ownerId, accountId, type, message, eventKey, createdAt, readAt, AlertFacts.NONE);
    }

    public boolean read() {
        return readAt != null;
    }
}
