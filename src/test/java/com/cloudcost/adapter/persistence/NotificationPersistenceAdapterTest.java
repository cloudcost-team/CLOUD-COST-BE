package com.cloudcost.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** 알림 저장 (#35 리뷰): 같은 발생 키만 "이미 있음"으로 보고, 다른 무결성 위반은 숨기지 않는다 */
class NotificationPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired NotificationRepository notifications;

    private Notification notification(UUID accountId, String key) {
        return new Notification(UUID.randomUUID(), owner, accountId, NotificationType.BUDGET, "문구", key, NOW, null);
    }

    @Test
    void 같은_발생_키는_한_번만_저장하고_없는_연결의_알림은_예외를_숨기지_않는다() throws Exception {
        UUID accountId = UUID.fromString(activeAccount());

        assertThat(notifications.insertIfAbsent(notification(accountId, "BUDGET:a:2026-10:80"))).isTrue();
        assertThat(notifications.insertIfAbsent(notification(accountId, "BUDGET:a:2026-10:80"))).isFalse();

        assertThatThrownBy(() -> notifications.insertIfAbsent(notification(UUID.randomUUID(), "BUDGET:b:2026-10:80")))
            .as("지워진 연결(FK 위반)은 중복으로 삼키지 않는다").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 발생_키_UNIQUE_위반만_중복으로_본다() {
        assertThat(NotificationPersistenceAdapter.isEventKeyDuplicate(new DataIntegrityViolationException("x",
            new RuntimeException("duplicate key value violates unique constraint \"uq_notifications_owner_event\""))))
            .isTrue();
        assertThat(NotificationPersistenceAdapter.isEventKeyDuplicate(new DataIntegrityViolationException(
            "Unique index or primary key violation: \"PUBLIC.UQ_NOTIFICATIONS_OWNER_EVENT_INDEX_A ON X\""))).isTrue();
        assertThat(NotificationPersistenceAdapter.isEventKeyDuplicate(new DataIntegrityViolationException(
            "Referential integrity constraint violation: \"FK_ACCOUNT\""))).isFalse();
    }
}
