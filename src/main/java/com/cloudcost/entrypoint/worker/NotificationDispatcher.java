package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.NotificationDispatchService;
import com.cloudcost.application.NotificationDispatchService.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 외부 채널(Discord)로 보낼 알림을 보낸다 (ADR-019). 서버 1대라 한 스레드에서만 돈다. */
@Component
@ConditionalOnProperty(prefix = "cci.notification", name = "dispatch-enabled", havingValue = "true", matchIfMissing = true)
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationDispatchService service;

    NotificationDispatcher(NotificationDispatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${cci.notification.dispatch-interval:60s}", initialDelayString = "30s")
    void dispatch() {
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
