package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객 계정의 IAM Role ARN. 파티션은 aws만 허용한다.
 */
public record RoleArn(String accountId, String path, String roleName) {

    private static final Pattern PATTERN =
        Pattern.compile("^arn:aws:iam::(\\d{12}):role/((?:[\\w+=,.@-]+/)*)([\\w+=,.@-]{1,64})$");

    public static final String INVALID_MESSAGE =
        "Role ARN 형식이 올바르지 않습니다. 예: arn:aws:iam::123456789012:role/CloudCostReadOnlyRole";

    public static RoleArn parse(String raw) {
        Matcher m = PATTERN.matcher(raw == null ? "" : raw.trim());
        if (!m.matches()) {
            throw new DomainValidationException("INVALID_ROLE_ARN", INVALID_MESSAGE);
        }
        return new RoleArn(m.group(1), m.group(2), m.group(3));
    }

    public String value() {
        return "arn:aws:iam::" + accountId + ":role/" + path + roleName;
    }

    /**
     * 로그·메시지용 마스킹: 1234****9012
     */
    public static String maskAccountId(String accountId) {
        if (accountId == null || accountId.length() != 12) {
            return "-";
        }
        return accountId.substring(0, 4) + "****" + accountId.substring(8);
    }

    @Override
    public String toString() {
        return "RoleArn(account=" + maskAccountId(accountId) + ", role=" + roleName + ")";
    }
}
