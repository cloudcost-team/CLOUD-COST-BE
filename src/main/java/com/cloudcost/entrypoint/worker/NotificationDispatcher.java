package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.NotificationDispatchService;
import com.cloudcost.application.NotificationDispatchService.Result;
import com.cloudcost.application.port.ClusterLock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 외부 채널(Discord)로 보낼 알림을 보낸다 (ADR-019). 서버가 여러 대여도 한 번에 한 곳에서만 보낸다 (ADR-021). */
@Component
@ConditionalOnProperty(prefix = "cci.notification", name = "dispatch-enabled", havingValue = "true", matchIfMissing = true)
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationDispatchService service;
    private final ClusterLock lock;

    NotificationDispatcher(NotificationDispatchService service, ClusterLock lock) {
        this.service = service;
        this.lock = lock;
    }

    @Scheduled(fixedDelayString = "${cci.notification.dispatch-interval:60s}", initialDelayString = "30s")
    void dispatch() {
        lock.runIfFree("notification-dispatch", Duration.ofSeconds(30), Duration.ofMinutes(5), this::dispatchNow);
    }

    void dispatchNow() {
        try {
            Result r = service.dispatch();
            if (r.queued() + r.sent() + r.failed() > 0) {
                log.info("Notification dispatch: queued={} sent={} failed={}", r.queued(), r.sent(), r.failed());
            }
        } catch (RuntimeException e) {
            log.warn("Notification dispatch failed: {}", e.getClass().getSimpleName());
        }
    }
}
