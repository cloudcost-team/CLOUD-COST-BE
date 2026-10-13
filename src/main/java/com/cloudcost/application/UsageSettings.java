package com.cloudcost.application;

import com.cloudcost.domain.usage.RefreshPolicy;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 수집·조회 설정 (값은 entrypoint에서 환경변수로 채운다).
 *
 * @param instanceTtl             DescribeInstances 재조회 간격 (기본 10분)
 * @param usageTtl                CloudWatch 재조회 간격 (기본 1시간). 그 안의 재조회는 DB 값으로 응답한다.
 * @param backfill                처음 수집할 때 소급하는 기간 (기본 90일, 최대 455일)
 * @param overlap                 직전 수집의 마지막 시간을 다시 읽는 겹침 구간 (기본 2시간)
 * @param maxCwRequestsPerRefresh 수집 1회의 CloudWatch 호출 상한. 넘으면 다음 수집으로 미룬다.
 * @param zone                    일별·월별 날짜 경계 (기본 Asia/Seoul)
 * @param priceTtl                단가 캐시 기간 (기본 7일)
 * @param missingPriceTtl         "단가 없음" 결과 캐시 기간 (기본 1일)
 */
public record UsageSettings(
    Duration instanceTtl,
    Duration usageTtl,
    Duration backfill,
    Duration overlap,
    int maxCwRequestsPerRefresh,
    ZoneId zone,
    Duration priceTtl,
    Duration missingPriceTtl) {

    public RefreshPolicy refreshPolicy() {
        return new RefreshPolicy(instanceTtl, usageTtl);
    }

    public static UsageSettings defaults() {
        return new UsageSettings(
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofDays(90),
            Duration.ofHours(2),
            500,
            ZoneId.of("Asia/Seoul"),
            Duration.ofDays(7),
            Duration.ofDays(1));
    }
}
