package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.ScheduledCollectionService;
import com.cloudcost.application.ScheduledCollectionService.Target;
import com.cloudcost.application.port.ClusterLock;
import com.cloudcost.application.port.CollectionQueue;
import java.time.Duration;
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
    private final ClusterLock lock;

    CollectionScheduler(ScheduledCollectionService service, CollectionQueue queue, ClusterLock lock) {
        this.service = service;
        this.queue = queue;
        this.lock = lock;
    }

    @Scheduled(cron = "${cci.collection.cron:0 0 3,9,15,21 * * *}", zone = "${cci.usage.zone:Asia/Seoul}")
    void enqueueAll() {
        // 서버가 여러 대면 모두 같은 시각에 깨어난다. 한 곳만 큐에 넣고, 10분 동안은 같은 회차를 다시 넣지 않는다 (ADR-021)
        boolean ran = lock.runIfFree("collection-scheduler", Duration.ofMinutes(10), Duration.ofMinutes(30), this::enqueueNow);
        if (!ran) {
            log.info("Scheduled collection skipped: another instance is enqueuing");
        }
    }

    void enqueueNow() {
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
