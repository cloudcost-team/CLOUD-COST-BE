package com.cloudcost.domain.account;

/**
 * 허용되지 않은 연결 상태 전이. 애플리케이션이 먼저 상태를 확인하므로 정상 흐름에서는 생기지 않는다.
 */
public class IllegalStatusTransitionException extends IllegalStateException {

    public IllegalStatusTransitionException(AwsAccount.Status from, AwsAccount.Status to, String reason) {
        super("연결 상태를 " + from + "에서 " + to + "(으)로 바꿀 수 없습니다: " + reason);
    }
}
