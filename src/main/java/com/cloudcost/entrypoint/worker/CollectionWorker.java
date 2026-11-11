package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.ScheduledCollectionService;
import com.cloudcost.application.ScheduledCollectionService.Outcome;
import com.cloudcost.application.port.CollectionQueue;
import com.cloudcost.application.port.CollectionQueue.Received;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 수집 큐를 계속 확인해 연결 1개씩 수집한다 (ADR-019, StackCallbackPoller와 같은 방식).
 * 처리에 실패한 메시지는 지우지 않아 잠시 뒤 다시 받고, 3번 실패하면 큐 설정에 따라 DLQ로 간다.
 */
@Component
@ConditionalOnExpression("!'${cci.aws.collection-queue-url:}'.isEmpty()")
class CollectionWorker {

    private static final Logger log = LoggerFactory.getLogger(CollectionWorker.class);

    private final CollectionQueue queue;
    private final ScheduledCollectionService service;

    CollectionWorker(CollectionQueue queue, ScheduledCollectionService service) {
        this.queue = queue;
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        try {
            for (Received message : queue.receive()) {
                process(message);
            }
        } catch (RuntimeException e) {
            log.warn("Collection queue polling failed: {}", e.getClass().getSimpleName());
        }
    }

    void process(Received message) {
        if (message.ownerId() == null || message.accountId() == null) {
            queue.delete(message.receiptHandle());
            return;
        }
        try {
            Outcome outcome = service.collect(message.ownerId(), message.accountId());
            queue.delete(message.receiptHandle());
            log.info("Scheduled collection {}: connection={}", outcome, message.accountId());
        } catch (RuntimeException e) {
            log.warn("Scheduled collection failed, will retry: connection={} attempt={} error={}",
                message.accountId(), message.receiveCount(), e.getClass().getSimpleName());
        }
    }
}
