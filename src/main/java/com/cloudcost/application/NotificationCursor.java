package com.cloudcost.application;

import com.cloudcost.application.port.NotificationRepository.Cursor;
import com.cloudcost.domain.DomainValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** 알림 목록 커서 문자열 ↔ 위치 (created_at, id). 프론트는 받은 값을 그대로 다시 보낸다 */
final class NotificationCursor {

    private NotificationCursor() {
    }

    static String encode(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        String raw = cursor.createdAt().getEpochSecond() + "." + cursor.createdAt().getNano() + ":" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            int dot = raw.indexOf('.');
            if (colon < 0 || dot < 0 || dot > colon) {
                throw new IllegalArgumentException(raw);
            }
            Instant at = Instant.ofEpochSecond(
                Long.parseLong(raw.substring(0, dot)), Long.parseLong(raw.substring(dot + 1, colon)));
            return new Cursor(at, UUID.fromString(raw.substring(colon + 1)));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new DomainValidationException("VALIDATION_ERROR", "cursor 값이 올바르지 않습니다. 받은 nextCursor를 그대로 보내 주세요.");
        }
    }
}
