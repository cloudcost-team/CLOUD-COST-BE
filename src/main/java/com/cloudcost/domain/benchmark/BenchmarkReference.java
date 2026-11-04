package com.cloudcost.domain.benchmark;

import java.math.BigDecimal;
import java.util.List;

/**
 * 팀 벤치마킹 비교 기준 (이슈 #33). 외부 자료의 숫자라 코드가 아니라 교체 가능한 리소스 파일에서 읽는다 (ADR-013).
 *
 * @param oversized 과다 사양 서버 비율 기준
 * @param waste     전체 낭비 비율 참고값 (지금 권한으로는 우리 값을 계산할 수 없다)
 * @param spend     월 지출 범위
 */
public record BenchmarkReference(Oversized oversized, Waste waste, Spend spend) {

    /**
     * @param percentiles "팀의 p%가 이 비율 이하" 표 (p 오름차순). 없으면 빈 목록 → "우리보다 효율적인 팀 %"를 계산하지 않는다
     */
    public record Oversized(
        String source,
        String description,
        int sampleSize,
        BigDecimal averagePercent,
        BigDecimal medianPercent,
        List<Percentile> percentiles) {

        public Oversized {
            percentiles = percentiles == null ? List.of() : List.copyOf(percentiles);
        }
    }

    /** 팀의 percentile%가 과다 사양 비율 ratioPercent 이하 */
    public record Percentile(BigDecimal percentile, BigDecimal ratioPercent) {
    }

    public record Waste(String source, String description, BigDecimal averagePercent) {
    }

    /** 비슷한 팀의 월 지출 범위 (USD) */
    public record Spend(String source, String description, BigDecimal minUsd, BigDecimal maxUsd) {
    }
}
