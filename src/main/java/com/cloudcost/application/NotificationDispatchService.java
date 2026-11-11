package com.cloudcost.application;

import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.application.port.NotificationDeliveryRepository;
import com.cloudcost.application.port.NotificationDeliveryRepository.Due;
import com.cloudcost.application.port.NotificationDeliveryRepository.Undelivered;
import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.Notification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 외부 채널 발송 (ADR-019, Outbox). 알림 평가는 알림 행만 저장하고, 이 서비스가 따로 돌면서
 * ① 켜진 채널마다 아직 발송 기록이 없는 알림의 기록(PENDING)을 만들고 ② 차례가 된 것을 보내 결과를 남긴다.
 * 발송이 실패해도 알림 행은 그대로라 서비스 안 알림창에는 남고, 실패한 발송은 간격을 늘려 다시 보낸다.
 */
public class NotificationDispatchService {

    /** 채널 등록 전이나 너무 오래된 알림은 보내지 않는다 */
    static final Duration MAX_AGE = Duration.ofDays(3);
    static final int BATCH = 50;

    /** 한 번 돌 때 만든·보낸 수 */
    public record Result(int queued, int sent, int failed) {}

    private final NotificationDeliveryRepository deliveries;
    private final NotificationChannelGateway gateway;
    private final Clock clock;
    private final String appUrl;

    /** @param appUrl 메시지 끝에 붙일 서비스 주소 (비어 있으면 붙이지 않는다) */
    public NotificationDispatchService(
        NotificationDeliveryRepository deliveries, NotificationChannelGateway gateway, Clock clock, String appUrl) {
        this.deliveries = deliveries;
        this.gateway = gateway;
        this.clock = clock;
        this.appUrl = appUrl == null ? "" : appUrl.trim();
    }

    public Result dispatch() {
        Instant now = clock.instant();
        int queued = 0;
        for (Undelivered u : deliveries.findUndelivered(now.minus(MAX_AGE), BATCH)) {
            if (deliveries.insertIfAbsent(
                Delivery.pending(UUID.randomUUID(), u.ownerId(), u.notificationId(), u.channelId(), now))) {
                queued++;
            }
        }
        int sent = 0;
        int failed = 0;
        for (Due due : deliveries.findDue(now, BATCH)) {
            SendResult result = gateway.send(due.channel(), text(due.notification(), due.accountName()));
            Instant at = clock.instant();
            if (result.ok()) {
                deliveries.save(due.delivery().sent(at));
                sent++;
            } else {
                deliveries.save(due.delivery().failed(result.reason(), result.retryable(), at));
                failed++;
            }
        }
        return new Result(queued, sent, failed);
    }

    /** @param accountName 연결 별명 (없으면 null → "이름 없는 연결") */
    String text(Notification n, String accountName) {
        StringBuilder sb = new StringBuilder("[CloudCost] ").append(n.type().label())
            .append(" · ").append(accountName == null ? NotificationService.UNNAMED : accountName);
        sb.append('\n').append(n.message());
        if (!appUrl.isEmpty()) {
            sb.append('\n').append(appUrl.replaceAll("/+$", "")).append("/notifications");
        }
        return sb.toString();
    }
}
