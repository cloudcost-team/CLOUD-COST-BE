package com.cloudcost.domain.account;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CloudFormation 빠른 생성(quick-create) 링크. 사용자가 누르면 값이 모두 채워진 스택 생성 화면이 열린다.
 *
 * <p>Custom Resource의 SNS 토픽은 스택과 같은 리전에 있어야 하므로, 스택 리전은 토픽 리전으로 정한다.
 * IAM Role은 글로벌이라 스택 리전과 관계없이 동작하고, 조회 리전은 MonitoredRegion 파라미터로 따로 넘긴다.
 */
public final class QuickCreateLink {

    public static final String STACK_NAME = "CloudCostInsight";
    public static final String ROLE_NAME = "CloudCostReadOnlyRole";

    private static final Pattern SNS_ARN = Pattern.compile("^arn:aws:sns:([a-z0-9-]+):\\d{12}:[\\w-]{1,256}$");

    private QuickCreateLink() {}

    /** @return 형식이 맞으면 토픽 리전, 아니면 null */
    public static String topicRegion(String callbackTopicArn) {
        Matcher m = SNS_ARN.matcher(callbackTopicArn == null ? "" : callbackTopicArn.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** 스택 파라미터 (템플릿 Parameters 이름과 같아야 한다) */
    public static Map<String, String> parameters(
            AwsAccount account, String backendRoleArn, String callbackTopicArn) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ExternalId", account.externalId().value());
        params.put("TrustedPrincipalArn", backendRoleArn);
        params.put("ConnectionId", account.id().toString());
        params.put("CallbackTopicArn", callbackTopicArn);
        params.put("MonitoredRegion", account.region().value());
        params.put("RoleName", ROLE_NAME);
        return params;
    }

    public static String build(String templateUrl, String stackRegion, Map<String, String> parameters) {
        StringBuilder url = new StringBuilder()
                .append("https://").append(stackRegion).append(".console.aws.amazon.com/cloudformation/home?region=")
                .append(stackRegion)
                .append("#/stacks/quickcreate?templateURL=").append(encode(templateUrl))
                .append("&stackName=").append(STACK_NAME);
        parameters.forEach((name, value) -> url.append("&param_").append(name).append('=').append(encode(value)));
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
