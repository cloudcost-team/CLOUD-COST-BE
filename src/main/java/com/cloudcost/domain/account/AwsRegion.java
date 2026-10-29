package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EC2·CloudWatch를 조회할 고객 계정 리전 (예: ap-northeast-2).
 */
public record AwsRegion(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z]{2}(-gov)?-[a-z]+-\\d$");

    public static final String DEFAULT = "ap-northeast-2";

    private static final Map<String, String> KOREAN_LABELS = Map.of(
        "ap-northeast-2", "서울",
        "ap-northeast-1", "도쿄",
        "us-east-1", "버지니아 북부",
        "us-west-2", "오레곤");

    public AwsRegion {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new DomainValidationException(
                "INVALID_REGION", "리전 형식이 올바르지 않습니다. 예: ap-northeast-2");
        }
    }

    public static AwsRegion ofNullable(String value) {
        return new AwsRegion(value == null || value.isBlank() ? DEFAULT : value.trim());
    }

    /** 화면 표시용 한국어 이름 (예: 서울). 모르는 리전은 코드 그대로 돌려준다. */
    public String koreanLabel() {
        return KOREAN_LABELS.getOrDefault(value, value);
    }

    @Override
    public String toString() {
        return value;
    }
}
