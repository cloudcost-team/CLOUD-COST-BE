package com.cloudcost.application;

import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.application.port.NotificationChannelGateway.Tone;
import com.cloudcost.application.port.NotificationChannelRepository;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.alert.NotificationChannel;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** 외부 알림 채널 등록·수정·삭제·테스트 발송 (ADR-019). 모두 내 채널만. */
public class NotificationChannelService {

    /** 실제 알림과 같은 embed 형식 */
    static final Message TEST_MESSAGE = new Message("테스트 알림",
        "CloudCost 알림 채널이 연결됐어요. 켜 둔 알림이 생기면 이 채널로 보내 드려요.", Tone.INFO, List.of(),
        "CloudCost Insight", null);

    private final NotificationChannelRepository channels;
    private final NotificationChannelGateway gateway;
    private final Clock clock;

    public NotificationChannelService(
        NotificationChannelRepository channels, NotificationChannelGateway gateway, Clock clock) {
        this.channels = channels;
        this.gateway = gateway;
        this.clock = clock;
    }

    public List<NotificationChannel> list(String ownerId) {
        return channels.findAllByOwnerId(ownerId);
    }

    public NotificationChannel create(String ownerId, String webhookUrl, boolean enabled) {
        if (channels.findAllByOwnerId(ownerId).size() >= NotificationChannel.MAX_PER_OWNER) {
            throw new DomainValidationException("CHANNEL_LIMIT_EXCEEDED",
                "알림 채널은 " + NotificationChannel.MAX_PER_OWNER + "개까지 등록할 수 있어요.");
        }
        return channels.save(NotificationChannel.discord(UUID.randomUUID(), ownerId, webhookUrl, enabled, clock.instant()));
    }

    /** @param webhookUrl null이면 주소는 그대로 */
    public NotificationChannel update(String ownerId, UUID id, String webhookUrl, boolean enabled) {
        return channels.save(get(ownerId, id).changed(webhookUrl, enabled, clock.instant()));
    }

    public void delete(String ownerId, UUID id) {
        if (!channels.deleteByOwnerIdAndId(ownerId, id)) {
            throw notFound();
        }
    }

    /** 꺼져 있어도 보낸다 (켜기 전에 확인하는 용도). 결과는 저장하지 않는다. */
    public SendResult test(String ownerId, UUID id) {
        return gateway.send(get(ownerId, id), TEST_MESSAGE);
    }

    private NotificationChannel get(String ownerId, UUID id) {
        return channels.findByOwnerIdAndId(ownerId, id).orElseThrow(NotificationChannelService::notFound);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("CHANNEL_NOT_FOUND", "알림 채널을 찾을 수 없어요.");
    }
}
