package com.cloudcost.adapter.awsinternal;

import com.cloudcost.domain.account.StackCallback;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SQS 메시지 본문 → StackCallback.
 *
 * <p>SNS가 SQS로 전달하면 본문은 SNS 봉투({"Type":"Notification","Message":"..."})이고, 그 Message 안에
 * CloudFormation Custom Resource 요청 JSON이 문자열로 들어 있다. Raw message delivery를 켜면 요청 JSON이 바로 온다.
 */
final class StackCallbackParser {

    private StackCallbackParser() {}

    /** @return 해석할 수 없으면 null */
    static StackCallback parse(String body, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.has("Message") && "Notification".equals(root.path("Type").asText())) {
                root = mapper.readTree(root.path("Message").asText());
            }
            if (!root.has("RequestType") || !root.has("ResponseURL")) {
                return null;
            }
            JsonNode props = root.path("ResourceProperties");
            return new StackCallback(
                    StackCallback.RequestType.parse(root.path("RequestType").asText()),
                    text(props, "ConnectionId"),
                    text(props, "ExternalId"),
                    text(props, "RoleArn"),
                    text(root, "StackId"),
                    text(root, "RequestId"),
                    text(root, "LogicalResourceId"),
                    text(root, "PhysicalResourceId"),
                    text(root, "ResponseURL"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
