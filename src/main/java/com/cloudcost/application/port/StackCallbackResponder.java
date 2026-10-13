package com.cloudcost.application.port;

import com.cloudcost.domain.account.StackCallback;

/**
 * CloudFormation에 Custom Resource 결과를 돌려준다 (ResponseURL로 PUT).
 * 응답하지 않으면 고객 스택이 최대 1시간 기다리다 실패한다.
 */
public interface StackCallbackResponder {

    /** @param reason FAILED일 때 고객 CloudFormation 콘솔 이벤트에 보이는 문구 */
    void respond(StackCallback callback, boolean success, String reason);
}
