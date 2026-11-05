package com.cloudcost.application.port;

import com.cloudcost.domain.alert.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 서비스 안 알림. 모든 조회·수정은 owner 조건을 건다 */
public interface NotificationRepository {

    /** 위치 (created_at, id). 이 위치보다 오래된 알림부터 준다 */
    record Cursor(Instant createdAt, UUID id) {
    }

    /**
     * 같은 (owner, eventKey)가 없을 때만 저장한다.
     *
     * @return 새로 저장했으면 true
     */
    boolean insertIfAbsent(Notification notification);

    /** 최신순 (created_at DESC, id DESC). cursor가 null이면 처음부터 */
    List<Notification> findPage(String ownerId, Cursor cursor, int limit);

    long countUnread(String ownerId);

    /** @return 읽음 처리한 수 */
    int markAllRead(String ownerId, Instant readAt);

    /** @return 내 알림이 있으면 true (이미 읽었어도 true) */
    boolean markRead(String ownerId, UUID id, Instant readAt);

    /** 연결 삭제용 */
    int deleteByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
