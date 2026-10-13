package com.cloudcost.domain.cost;

/**
 * 응답에 붙이는 비용 관련 안내 문구. 금액이 청구서 금액처럼 보이지 않게 한다.
 */
public final class CostNotes {

    public static final String COST_BASIS = "estimated_on_demand";
    public static final String CURRENCY = "USD";

    public static final String ESTIMATE =
        "추정 비용입니다: 사용 시간 × 온디맨드 시간당 단가. 실제 AWS 청구 금액이 아닙니다.";
    public static final String EXCLUDED =
        "Reserved Instance·Savings Plans·Spot 할인, EBS, 데이터 전송 비용은 포함하지 않습니다.";
    public static final String USAGE_METHOD =
        "사용 시간은 CloudWatch에 지표(CPUUtilization)가 기록된 시간으로 계산합니다 (1분 단위).";
    public static final String HISTORY_LIMIT =
        "CloudCost Insight 연결 시점에 이미 삭제된 인스턴스와 소급 수집 기간 이전의 사용량은 조회할 수 없습니다.";
    public static final String PRICE_NOT_FOUND = "해당 인스턴스의 온디맨드 단가를 조회할 수 없습니다.";
    public static final String UNSUPPORTED_PLATFORM = "지원하지 않는 OS/테넌시라 온디맨드 단가를 조회하지 않았습니다.";
    public static final String SPOT =
        "Spot 인스턴스입니다. Spot 할인을 반영하지 않고 온디맨드 단가로 계산했습니다.";

    private CostNotes() {
    }
}
