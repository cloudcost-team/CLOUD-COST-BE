package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;
import java.util.regex.Pattern;

/**
 * EC2·CloudWatch를 조회할 고객 계정 리전 (예: ap-northeast-2).
 */
public record AwsRegion(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z]{2}(-gov)?-[a-z]+-\\d$");

    public static final String DEFAULT = "ap-northeast-2";

    public AwsRegion {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new DomainValidationException(
                "INVALID_REGION", "리전 형식이 올바르지 않습니다. 예: ap-northeast-2");
        }
    }

    public static AwsRegion ofNullable(String value) {
        return new AwsRegion(value == null || value.isBlank() ? DEFAULT : value.trim());
    }

    @Override
    public String toString() {
        return value;
    }
}
