package com.cloudcost.domain.account;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고객이 자기 계정에 만들 Read Only Role의 정책 문서.
 *
 * <p>허용 액션은 아래 7개(모두 읽기)뿐이다. 바꾸려면 사람 승인 + ADR이 먼저다 (docs/SECURITY.md, ADR-020).
 * Describe·GetMetricStatistics는 리소스 단위 권한을 지원하지 않아 Resource는 "*"다.
 * 잊힌 리전의 서버·리소스를 찾기 위해 리전 조건(aws:RequestedRegion)은 두지 않는다 (ADR-020).
 */
public final class CustomerRolePolicy {

    public static final List<String> ALLOWED_ACTIONS = List.of(
        "ec2:DescribeInstances",
        "cloudwatch:GetMetricStatistics",
        "ec2:DescribeRegions",
        "ec2:DescribeVolumes",
        "ec2:DescribeAddresses",
        "ec2:DescribeSnapshots",
        "elasticloadbalancing:DescribeLoadBalancers");

    private CustomerRolePolicy() {
    }

    public static Map<String, Object> permissionPolicy() {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Sid", "CloudCostInsightReadOnly");
        statement.put("Effect", "Allow");
        statement.put("Action", ALLOWED_ACTIONS);
        statement.put("Resource", "*");
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
