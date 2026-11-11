package com.cloudcost.domain.alert;

import java.time.Instant;
import java.util.UUID;

/**
 * 사용자가 등록한 외부 알림 채널 1개. 켜져 있으면 등록한 뒤에 생긴 알림을 이 채널로도 보낸다.
 *
 * @param webhookUrl 비밀 값. API 응답·로그에는 {@link #maskedTarget()}만 쓴다
 */
public record NotificationChannel(
    UUID id,
    String ownerId,
    ChannelType type,
    String webhookUrl,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

    public static final int MAX_PER_OWNER = 5;

    public static NotificationChannel discord(UUID id, String ownerId, String webhookUrl, boolean enabled, Instant now) {
        return new NotificationChannel(id, ownerId, ChannelType.DISCORD, DiscordWebhook.validate(webhookUrl), enabled,
            now, now);
    }

    /** @param webhookUrl null이면 주소는 그대로 */
    public NotificationChannel changed(String webhookUrl, boolean enabled, Instant now) {
        String url = webhookUrl == null ? this.webhookUrl : DiscordWebhook.validate(webhookUrl);
        return new NotificationChannel(id, ownerId, type, url, enabled, createdAt, now);
    }

    public String maskedTarget() {
        return DiscordWebhook.mask(webhookUrl);
    }
}
