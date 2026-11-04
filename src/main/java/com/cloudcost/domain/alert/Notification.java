package com.cloudcost.domain.alert;

import java.time.Instant;
import java.util.UUID;

/**
 * 서비스 안 알림 1건. 같은 일은 eventKey로 한 번만 만든다 (owner별 UNIQUE).
 *
 * @param message 만들 때 정한 한국어 문구 (금액은 그때의 통화·환율로 적는다)
 * @param readAt  읽지 않았으면 null
 */
public record Notification(
    UUID id,
    String ownerId,
    UUID accountId,
    NotificationType type,
    String message,
    String eventKey,
    Instant createdAt,
    Instant readAt) {

    public boolean read() {
        return readAt != null;
    }
}
