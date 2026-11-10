package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EC2·CloudWatch를 조회할 고객 계정 리전 (예: ap-northeast-2).
 */
public record AwsRegion(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z]{2}(-gov)?-[a-z]+-\\d$");

    public static final String DEFAULT = "ap-northeast-2";

    /**
     * 연결할 때 고를 수 있는 리전과 한국어 이름 (화면 순서). 계정마다 따로 켜야 하는 옵트인 리전은 넣지 않는다
     * (켜지 않은 계정에서는 AWS_REGION_DISABLED로 실패한다).
     */
    private static final Map<String, String> KOREAN_LABELS = labels(
        "ap-northeast-2", "서울",
        "ap-northeast-1", "도쿄",
        "ap-northeast-3", "오사카",
        "ap-southeast-1", "싱가포르",
        "ap-southeast-2", "시드니",
        "ap-south-1", "뭄바이",
        "us-east-1", "버지니아 북부",
        "us-east-2", "오하이오",
        "us-west-1", "캘리포니아 북부",
        "us-west-2", "오레곤",
        "ca-central-1", "캐나다 중부",
        "eu-central-1", "프랑크푸르트",
        "eu-west-1", "아일랜드",
        "eu-west-2", "런던",
        "eu-west-3", "파리",
        "eu-north-1", "스톡홀름",
        "sa-east-1", "상파울루");

    /** 연결 화면에서 고를 수 있는 리전 (첫 번째가 기본값). */
    public static List<AwsRegion> selectable() {
        return KOREAN_LABELS.keySet().stream().map(AwsRegion::new).toList();
    }

    private static Map<String, String> labels(String... codeAndLabel) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < codeAndLabel.length; i += 2) {
            map.put(codeAndLabel[i], codeAndLabel[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

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
