package com.cloudcost.application.port;

import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationChannel;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 외부 알림 발송 기록 (ADR-019). notifications 행이 보낼 일(Outbox)이고, 이 저장소는 채널별 발송 상태를 둔다.
 * {@link #findUndelivered}·{@link #findDue}는 발송 작업 전용이다: 모든 사용자를 훑고, owner는 결과 행에서 읽는다.
 * 사용자 요청 경로에서는 쓰지 않는다.
 */
public interface NotificationDeliveryRepository {

    /** 켜진 채널 × (그 채널 등록 뒤, since 이후에 생긴) 알림 중 아직 발송 기록이 없는 쌍 */
    record Undelivered(String ownerId, UUID notificationId, UUID channelId) {}

    /** 보낼 차례가 된 발송 1건과 보낼 내용·채널 */
    record Due(Delivery delivery, Notification notification, NotificationChannel channel, String accountName) {}

    List<Undelivered> findUndelivered(Instant since, int limit);

    /** 같은 (알림, 채널)이 없을 때만 저장한다. @return 새로 저장했으면 true */
    boolean insertIfAbsent(Delivery delivery);

    /** PENDING이고 nextAttemptAt이 지났으며 채널이 켜져 있는 것, 오래된 순 */
    List<Due> findDue(Instant now, int limit);

    void save(Delivery delivery);

    List<Delivery> findByOwnerIdAndNotificationIds(String ownerId, Collection<UUID> notificationIds);
}
