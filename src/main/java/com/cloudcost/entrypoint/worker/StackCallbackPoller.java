package com.cloudcost.entrypoint.worker;

import com.cloudcost.application.StackCallbackService;
import com.cloudcost.application.port.StackCallbackInbox;
import com.cloudcost.application.port.StackCallbackInbox.Received;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스택 신호 우편함(SQS)을 계속 확인한다. CCI_CALLBACK_QUEUE_URL이 있을 때만 켜진다.
 * 처리에 실패한 메시지는 지우지 않아 잠시 뒤 다시 처리된다.
 */
@Component
@ConditionalOnExpression("!'${cci.aws.callback-queue-url:}'.isEmpty()")
class StackCallbackPoller {

    private static final Logger log = LoggerFactory.getLogger(StackCallbackPoller.class);

    private final StackCallbackInbox inbox;
    private final StackCallbackService service;

    StackCallbackPoller(StackCallbackInbox inbox, StackCallbackService service) {
        this.inbox = inbox;
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        try {
            for (Received message : inbox.receive()) {
                process(message);
            }
        } catch (RuntimeException e) {
            log.warn("Stack callback polling failed: {}", e.getClass().getSimpleName());
        }
    }

    void process(Received message) {
        if (message.callback() == null) {
            inbox.delete(message.receiptHandle());
            return;
        }
        try {
            service.handle(message.callback());
            inbox.delete(message.receiptHandle());
            log.info("Stack callback handled: type={} connection={}",
                    message.callback().requestType(), message.callback().connectionId());
        } catch (RuntimeException e) {
            log.warn("Stack callback failed, will retry: type={} error={}",
                    message.callback().requestType(), e.getClass().getSimpleName());
        }
    }
}
