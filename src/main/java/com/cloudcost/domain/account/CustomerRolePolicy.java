package com.cloudcost.domain.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고객이 자기 계정에 만들 Read Only Role의 정책 문서.
 *
 * <p>허용 액션은 아래 2개뿐이다. 바꾸려면 사람 승인 + ADR이 먼저다 (docs/SECURITY.md).
 * 두 액션은 리소스 단위 권한을 지원하지 않아 Resource는 "*"이고, 대신 aws:RequestedRegion으로 리전을 제한한다.
 */
public final class CustomerRolePolicy {

    public static final List<String> ALLOWED_ACTIONS =
        List.of("ec2:DescribeInstances", "cloudwatch:GetMetricStatistics");

    private CustomerRolePolicy() {
    }

    public static Map<String, Object> permissionPolicy(AwsRegion region) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "CloudCostInsightReadEc2Usage");
        statement.put("Effect", "Allow");
        statement.put("Action", ALLOWED_ACTIONS);
        statement.put("Resource", "*");
        statement.put(
            "Condition",
            Map.of("StringEquals", Map.of("aws:RequestedRegion", List.of(region.value()))));
        return document(statement);
    }

    /**
     * Principal은 우리 백엔드 Role 하나이고 sts:ExternalId 조건이 반드시 들어간다.
     */
    public static Map<String, Object> trustPolicy(String backendRoleArn, ExternalId externalId) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Principal", Map.of("AWS", backendRoleArn));
        statement.put("Action", "sts:AssumeRole");
        statement.put(
            "Condition", Map.of("StringEquals", Map.of("sts:ExternalId", externalId.value())));
        return document(statement);
    }

    private static Map<String, Object> document(Map<String, Object> statement) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("Version", "2012-10-17");
        doc.put("Statement", List.of(statement));
        return doc;
    }
}
