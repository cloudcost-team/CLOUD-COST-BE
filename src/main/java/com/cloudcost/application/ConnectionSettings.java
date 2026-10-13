package com.cloudcost.application;

import java.time.Duration;

/**
 * 연결 설정 (값은 entrypoint에서 환경변수로 채운다).
 *
 * @param backendRoleArn 고객 Trust Policy의 Principal (CCI_BACKEND_ROLE_ARN)
 * @param templateUrl S3에 올린 고객용 CloudFormation 템플릿 URL (CCI_TEMPLATE_URL)
 * @param callbackTopicArn 스택 생성·삭제 신호를 받는 우리 계정 SNS 토픽 (CCI_CALLBACK_TOPIC_ARN)
 * @param verifyAttempts 신호를 받은 뒤 AssumeRole 검증 시도 횟수. 막 만든 Role은 IAM 반영에 몇 초~수십 초가 걸린다.
 * @param verifyInterval 재시도 간격
 */
public record ConnectionSettings(
        String backendRoleArn,
        String templateUrl,
        String callbackTopicArn,
        int verifyAttempts,
        Duration verifyInterval) {

    public ConnectionSettings {
        backendRoleArn = blankToEmpty(backendRoleArn);
        templateUrl = blankToEmpty(templateUrl);
        callbackTopicArn = blankToEmpty(callbackTopicArn);
        verifyAttempts = Math.max(1, verifyAttempts);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
