package com.cloudcost.entrypoint.api;

import com.cloudcost.application.NotificationChannelService;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.alert.NotificationChannel;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.ChannelDtos;
import com.cloudcost.entrypoint.api.dto.ChannelDtos.ChannelDto;
import com.cloudcost.entrypoint.api.dto.ChannelDtos.ChannelListResponse;
import com.cloudcost.entrypoint.api.dto.ChannelDtos.CreateChannelRequest;
import com.cloudcost.entrypoint.api.dto.ChannelDtos.TestSendResponse;
import com.cloudcost.entrypoint.api.dto.ChannelDtos.UpdateChannelRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings/alerts/channels")
@Tag(name = "5. 알림 (서비스 안)",
    description = "외부 알림 채널: Discord 웹훅을 등록하면 새 알림을 그 채널로도 보낸다 (ADR-019).")
class NotificationChannelController {

    private final NotificationChannelService channels;

    NotificationChannelController(NotificationChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    @Operation(summary = "알림 채널 목록 (등록 순)")
    ChannelListResponse list(@CurrentOwner String owner) {
        return new ChannelListResponse(channels.list(owner).stream().map(ChannelDto::of).toList(),
            NotificationChannel.MAX_PER_OWNER, List.of(ChannelDtos.CHANNEL_NOTE));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "알림 채널 등록",
        description = "https://discord.com/api/webhooks/ 로 시작하는 주소만 받는다 (400 INVALID_WEBHOOK_URL). "
            + "5개를 넘으면 400 CHANNEL_LIMIT_EXCEEDED. 등록한 뒤에 생긴 알림부터 보낸다.")
    ChannelDto create(@CurrentOwner String owner, @Valid @RequestBody CreateChannelRequest request) {
        if (request.type() != null && !"DISCORD".equalsIgnoreCase(request.type())) {
            throw new DomainValidationException("UNSUPPORTED_CHANNEL", "지금은 Discord 채널만 등록할 수 있어요.");
        }
        return ChannelDto.of(channels.create(owner, request.webhookUrl(),
            request.enabled() == null || request.enabled()));
    }

    @PutMapping("/{channelId}")
    @Operation(summary = "알림 채널 수정 (켜기·끄기, 주소 바꾸기)", description = "남의 채널이면 404 CHANNEL_NOT_FOUND.")
    ChannelDto update(@CurrentOwner String owner, @PathVariable UUID channelId,
                      @Valid @RequestBody UpdateChannelRequest request) {
        String url = request.webhookUrl() == null || request.webhookUrl().isBlank() ? null : request.webhookUrl();
        return ChannelDto.of(channels.update(owner, channelId, url, request.enabled()));
    }

    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "알림 채널 삭제", description = "그 채널의 발송 기록도 지운다. 알림 자체는 남는다.")
    void delete(@CurrentOwner String owner, @PathVariable UUID channelId) {
        channels.delete(owner, channelId);
    }

    @PostMapping("/{channelId}/test")
    @Operation(summary = "테스트 발송", description = "꺼진 채널도 보낸다. 실패하면 ok=false와 한국어 사유 (HTTP는 200).")
    TestSendResponse test(@CurrentOwner String owner, @PathVariable UUID channelId) {
        return TestSendResponse.of(channels.test(owner, channelId));
    }
}
