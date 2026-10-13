package com.cloudcost.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 이번 응답이 AWS에서 새로 가져온 것인지, DB에 저장된 것인지.
 *
 * @param nextUsageRefreshAt 이 시각 전까지는 CloudWatch를 다시 부르지 않고 DB 값으로 응답한다
 * @param warnings           일부 수집 실패 등 (데이터는 마지막 성공 값)
 */
public record Freshness(
    Instant instancesRefreshedAt,
    boolean instancesFromCache,
    Instant usageRefreshedAt,
    boolean usageFromCache,
    Instant nextUsageRefreshAt,
    List<String> warnings) {

    public Freshness withWarnings(List<String> more) {
        if (more.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(warnings);
        all.addAll(more);
        return new Freshness(
            instancesRefreshedAt, instancesFromCache, usageRefreshedAt, usageFromCache, nextUsageRefreshAt,
            List.copyOf(all));
    }
}
