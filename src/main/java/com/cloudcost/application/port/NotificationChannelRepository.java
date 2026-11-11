package com.cloudcost.application.port;

import com.cloudcost.domain.alert.NotificationChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 외부 알림 채널. 모든 조회·수정은 owner 조건을 건다 */
public interface NotificationChannelRepository {

    NotificationChannel save(NotificationChannel channel);

    Optional<NotificationChannel> findByOwnerIdAndId(String ownerId, UUID id);

    /** 등록한 순서 */
    List<NotificationChannel> findAllByOwnerId(String ownerId);

    /** 그 채널의 발송 기록도 함께 지운다. @return 지웠으면 true */
    boolean deleteByOwnerIdAndId(String ownerId, UUID id);
}
