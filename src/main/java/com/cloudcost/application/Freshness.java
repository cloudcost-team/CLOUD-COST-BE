package com.cloudcost.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 이번 응답이 AWS에서 새로 가져온 것인지, DB에 저장된 것인지.
 *
 * @param nextUsageRefreshAt 이 시각 전까지는 CloudWatch를 다시 부르지 않고 DB 값으로 응답한다
 * @param warningDetails     일부 수집 실패 등 (데이터는 마지막 성공 값). 화면이 상태를 나눌 수 있게 코드를 함께 둔다
 */
public record Freshness(
    Instant instancesRefreshedAt,
    boolean instancesFromCache,
    Instant usageRefreshedAt,
    boolean usageFromCache,
    Instant nextUsageRefreshAt,
    List<Warning> warningDetails) {

    /** 수집 안내의 종류. 문구는 바뀔 수 있으니 화면은 이 코드로 나눈다 */
    public enum WarningCode {
        /** CloudWatch 호출 상한에 걸려 일부 서버만 수집함 (다음 수집 때 이어서 가져온다) */
        PARTIAL_COLLECTION,
        /** AWS 요청 빈도 제한에 걸려 이번 수집을 멈춤 */
        AWS_THROTTLED,
        /** 그 밖의 이유로 사용량 일부를 수집하지 못함 */
        USAGE_COLLECTION_FAILED,
        /** 이번 수집 전체가 실패해 마지막으로 저장된 데이터를 보여 줌 */
        COLLECTION_FAILED,
        /** 연결이 오류 상태라 저장된 데이터를 보여 줌 (고치면 자동 복구) */
        CONNECTION_ERROR,
        /** 온디맨드 단가를 가져오지 못한 서버가 있음 */
        PRICE_UNAVAILABLE
    }

    public record Warning(WarningCode code, String message) {
    }

    public Freshness {
        warningDetails = warningDetails == null ? List.of() : List.copyOf(warningDetails);
    }

    /** 안내 문구만 (기존 응답의 warnings) */
    public List<String> warnings() {
        return warningDetails.stream().map(Warning::message).toList();
    }

    public Freshness withWarnings(List<Warning> more) {
        if (more.isEmpty()) {
            return this;
        }
        List<Warning> all = new ArrayList<>(warningDetails);
        all.addAll(more);
        return new Freshness(
            instancesRefreshedAt, instancesFromCache, usageRefreshedAt, usageFromCache, nextUsageRefreshAt, all);
    }

    /** 같은 종류의 안내 여러 개 */
    public Freshness withWarnings(WarningCode code, List<String> messages) {
        return withWarnings(messages.stream().map(m -> new Warning(code, m)).toList());
    }
}
