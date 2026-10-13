package com.cloudcost.application.port;

import com.cloudcost.domain.account.StackCallback;
import java.util.List;

/** 스택 신호 우편함 (구현: adapter/awsinternal, 우리 계정의 SQS). */
public interface StackCallbackInbox {

    /** 기다렸다가(long polling) 받은 메시지들. */
    List<Received> receive();

    /** 처리를 끝낸 메시지를 지운다. 지우지 않으면 잠시 뒤 다시 받는다. */
    void delete(String receiptHandle);

    /** @param callback 해석할 수 없는 메시지면 null (지우고 버린다) */
    record Received(String receiptHandle, StackCallback callback) {}
}
