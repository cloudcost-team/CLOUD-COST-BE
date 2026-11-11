package com.cloudcost.application.port;

import java.util.List;
import java.util.UUID;

/**
 * 예약 수집 작업 큐 (구현: adapter/awsinternal, 우리 계정의 SQS + DLQ). ADR-019.
 * 메시지에는 owner와 연결 id만 담는다. 워커는 둘이 함께 맞는 연결을 DB에서 다시 읽어 쓴다 (메시지 값만 믿지 않는다).
 */
public interface CollectionQueue {

    void enqueue(String ownerId, UUID accountId);

    /** 기다렸다가(long polling) 받은 작업들 */
    List<Received> receive();

    /** 끝낸 작업을 지운다. 지우지 않으면 잠시 뒤 다시 받고, 여러 번 실패하면 DLQ로 간다 */
    void delete(String receiptHandle);

    /**
     * @param ownerId   해석할 수 없는 메시지면 null (지우고 버린다)
     * @param receiveCount 이 메시지를 받은 횟수 (1이면 처음)
     */
    record Received(String receiptHandle, String ownerId, UUID accountId, int receiveCount) {}
}
