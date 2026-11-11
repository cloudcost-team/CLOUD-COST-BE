package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.domain.alert.NotificationChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 외부 알림 채널 (ADR-019). 웹훅 주소는 저장 뒤 가린 값(target)만 돌려준다. */
public final class ChannelDtos {

    private ChannelDtos() {}

    public static final String CHANNEL_NOTE =
            "Discord 서버 설정 → 연동 → 웹후크에서 만든 주소를 넣어 주세요. 주소는 저장 뒤 일부만 보여 드려요.";

    public record CreateChannelRequest(
            @Schema(description = "DISCORD (지금은 Discord만)", example = "DISCORD") String type,
            @Schema(example = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz")
            @NotBlank(message = "웹훅 주소를 입력해 주세요") @Size(max = 300) String webhookUrl,
            @Schema(description = "기본 true") Boolean enabled) {}

    public record UpdateChannelRequest(
            @Schema(description = "바꿀 때만 보낸다. 없으면 주소는 그대로") @Size(max = 300) String webhookUrl,
            @NotNull(message = "켜기 여부를 보내 주세요") Boolean enabled) {}

    public record ChannelDto(
            UUID channelId,
            @Schema(example = "DISCORD") String type,
            @Schema(example = "Discord") String typeLabel,
            @Schema(example = "https://discord.com/api/webhooks/…5678/••••") String target,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {

        public static ChannelDto of(NotificationChannel c) {
            return new ChannelDto(c.id(), c.type().name(), c.type().label(), c.maskedTarget(), c.enabled(),
                    c.createdAt(), c.updatedAt());
        }
    }

    public record ChannelListResponse(List<ChannelDto> items, int maxChannels, List<String> notes) {}

    public record TestSendResponse(
            boolean ok,
            @Schema(description = "실패 사유 (한국어). 성공이면 null") String reason) {

        public static TestSendResponse of(SendResult r) {
            return new TestSendResponse(r.ok(), r.reason());
        }
    }
}
