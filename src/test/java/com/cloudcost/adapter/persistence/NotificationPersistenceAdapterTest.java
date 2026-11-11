package com.cloudcost.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.adapter.persistence.SpringDataRepositories.AlertSettingsJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.NotificationJpa;
import com.cloudcost.application.port.NotificationRepository;
import com.cloudcost.domain.alert.Notification;
import com.cloudcost.domain.alert.NotificationType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 알림 저장 (#35 리뷰): 같은 발생 키만 "이미 있음"으로 보고, 다른 무결성 위반은 숨기지 않는다 */
class NotificationPersistenceAdapterTest extends IntegrationTestBase {

    @Autowired NotificationRepository notifications;
    @Autowired NotificationJpa notificationJpa;
    @Autowired AlertSettingsJpa alertSettingsJpa;
    @Autowired SpringDataRepositories.NotificationDeliveryJpa deliveryJpa;
    @Autowired PlatformTransactionManager transactionManager;

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

    @Test
    void 존재_확인을_함께_통과한_경합에서도_UNIQUE_위반은_false로_끝난다() throws Exception {
        // 두 요청이 동시에 exists 검사를 통과한 상황: exists가 항상 false인 저장소로 재현한다
        NotificationJpa racing = (NotificationJpa) Proxy.newProxyInstance(NotificationJpa.class.getClassLoader(),
            new Class<?>[] {NotificationJpa.class}, (proxy, method, args) -> {
                if (method.getName().equals("existsByOwnerIdAndEventKey")) {
                    return false;
                }
                try {
                    return method.invoke(notificationJpa, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        NotificationPersistenceAdapter adapter =
            new NotificationPersistenceAdapter(racing, alertSettingsJpa, deliveryJpa, transactionManager);
        UUID accountId = UUID.fromString(activeAccount());

        // 바깥 트랜잭션 안에서 불러도 위반이 바깥을 rollback-only로 만들지 않는다 (커밋 때 UnexpectedRollbackException 없음)
        Boolean second = new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(adapter.insertIfAbsent(notification(accountId, "SPIKE:a:2026-10-05"))).isTrue();
            return adapter.insertIfAbsent(notification(accountId, "SPIKE:a:2026-10-05"));
        });

        assertThat(second).isFalse();
        assertThat(notifications.countUnread(owner)).isEqualTo(1);
    }
}
