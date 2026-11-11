package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.ScheduledCollectionService;
import com.cloudcost.application.ScheduledCollectionService.Target;
import com.cloudcost.application.port.CollectionQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정해진 시각(기본 KST 03·09·15·21시)마다 수집할 연결을 큐에 1건씩 넣는다 (ADR-019).
 * CCI_COLLECTION_QUEUE_URL이 있을 때만 켜진다.
 */
@Component
@ConditionalOnExpression("!'${cci.aws.collection-queue-url:}'.isEmpty()")
class CollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);

    private final ScheduledCollectionService service;
    private final CollectionQueue queue;

    CollectionScheduler(ScheduledCollectionService service, CollectionQueue queue) {
        this.service = service;
        this.queue = queue;
    }

    @Scheduled(cron = "${cci.collection.cron:0 0 3,9,15,21 * * *}", zone = "${cci.usage.zone:Asia/Seoul}")
    void enqueueAll() {
        int queued = 0;
        int failed = 0;
        for (Target t : service.targets()) {
            try {
                queue.enqueue(t.ownerId(), t.accountId());
                queued++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Collection enqueue failed: connection={} error={}", t.accountId(), e.getClass().getSimpleName());
            }
        }
        log.info("Scheduled collection queued: {} (failed {})", queued, failed);
    }
}
