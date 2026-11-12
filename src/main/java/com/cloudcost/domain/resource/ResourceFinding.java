package com.cloudcost.domain.resource;

import java.math.BigDecimal;
import java.util.List;

/**
 * 방치 리소스 1건 (ADR-020). 금액은 USD 월 추정치이고, 계산할 수 없으면 null이다.
 * 리소스를 바꾸지 않는다: action·consoleSteps는 사용자가 AWS 콘솔에서 직접 하는 방법이다.
 */
public record ResourceFinding(
    ResourceWasteKind kind,
    AwsResource resource,
    BigDecimal monthlyCostUsd,
    String evidence,
    String action,
    List<String> consoleSteps,
    List<String> cautions,
    String priceNote) {

    public String displayName() {
        return resource.name() == null || resource.name().isBlank() ? resource.resourceId() : resource.name();
    }

    /** 시뮬레이터 등이 같은 항목을 가리킬 때 쓰는 결정적 id */
    public String recommendationId() {
        return resource.region() + ":" + resource.resourceId() + ":" + kind.name();
    }
}
