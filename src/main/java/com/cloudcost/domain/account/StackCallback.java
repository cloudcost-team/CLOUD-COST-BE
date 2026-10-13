package com.cloudcost.domain.account;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객 계정의 CloudFormation이 Custom Resource로 보낸 신호 (SNS → SQS로 받음).
 *
 * <p>누구나 SNS 토픽에 메시지를 보낼 수 있으므로 이 내용은 믿지 않는다. 연결 id·External ID가 맞는지 확인하고,
 * 실제 AssumeRole이 성공해야만 연결된다.
 *
 * @param stackId 예: arn:aws:cloudformation:ap-northeast-2:123456789012:stack/CloudCostInsight/…
 * @param responseUrl CloudFormation이 결과를 기다리는 S3 presigned URL
 */
public record StackCallback(
        RequestType requestType,
        String connectionId,
        String externalId,
        String roleArn,
        String stackId,
        String requestId,
        String logicalResourceId,
        String physicalResourceId,
        String responseUrl) {

    public enum RequestType {
        CREATE,
        UPDATE,
        DELETE;

        public static RequestType parse(String value) {
            return switch (value == null ? "" : value) {
                case "Create" -> CREATE;
                case "Update" -> UPDATE;
                case "Delete" -> DELETE;
                default -> throw new IllegalArgumentException("unknown RequestType");
            };
        }
    }

    private static final Pattern STACK_ARN =
            Pattern.compile("^arn:aws:cloudformation:[a-z0-9-]+:(\\d{12}):stack/.+$");

    /** StackId에서 스택을 만든 AWS 계정 ID. 형식이 다르면 null. */
    public String stackAccountId() {
        Matcher m = STACK_ARN.matcher(stackId == null ? "" : stackId);
        return m.matches() ? m.group(1) : null;
    }

    /** Update에서 다른 값을 돌려주면 CloudFormation이 교체로 보고 Delete를 보내므로 연결마다 고정한다. */
    public String stablePhysicalId() {
        return "cci-connection-" + connectionId;
    }
}
