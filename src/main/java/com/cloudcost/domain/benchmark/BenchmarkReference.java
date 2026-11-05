package com.cloudcost.domain.benchmark;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 팀 벤치마킹 비교 기준 (이슈 #33). 외부 자료의 숫자라 코드가 아니라 교체 가능한 리소스 파일에서 읽는다 (ADR-013).
 *
 * @param oversized 과다 사양 서버 비율 기준 (서버 대수 구간별)
 * @param waste     전체 낭비 비율 참고값 (지금 권한으로는 우리 값을 계산할 수 없다)
 * @param spend     월 지출 범위
 */
public record BenchmarkReference(Oversized oversized, Waste waste, Spend spend) {

    /**
     * @param bands 서버(VM) 대수 구간별 분포. 구간은 겹치지 않고 작은 순서다
     */
    public record Oversized(String source, String description, List<SizeBand> bands) {

        public Oversized {
            bands = bands == null ? List.of() : List.copyOf(bands);
        }

        /** 서버 n대가 속한 구간. 어느 구간에도 안 들면 empty */
        public Optional<SizeBand> bandFor(int servers) {
            return bands.stream().filter(b -> b.contains(servers)).findFirst();
        }

        /** "1-2" 같은 구간 키로 찾는다 */
        public Optional<SizeBand> band(String key) {
            return bands.stream().filter(b -> b.key().equals(key)).findFirst();
        }
    }

    /**
     * 서버(VM) 대수 구간 하나의 분포. 아직 원본에서 계산하지 않은 구간은 sampleSize 0, 평균·중앙값 null이다.
     *
     * @param percentiles "팀의 p%가 이 비율 이하" 표 (p 오름차순). 없으면 빈 목록 → "우리보다 효율적인 팀 %"를 계산하지 않는다
     */
    public record SizeBand(
        int minServers,
        int maxServers,
        int sampleSize,
        BigDecimal averagePercent,
        BigDecimal medianPercent,
        List<Percentile> percentiles) {

        public SizeBand {
            percentiles = percentiles == null ? List.of() : List.copyOf(percentiles);
        }

        /** 예: "1-2" (API 요청의 sizeBand 값) */
        public String key() {
            return minServers + "-" + maxServers;
        }

        /** 예: "서버 1~2대" */
        public String label() {
            return minServers == maxServers ? "서버 " + minServers + "대" : "서버 " + minServers + "~" + maxServers + "대";
        }

        public boolean contains(int servers) {
            return servers >= minServers && servers <= maxServers;
        }

        /** 비교할 숫자가 있는지 (원본에서 계산해 채운 구간) */
        public boolean hasData() {
            return sampleSize > 0 && averagePercent != null && medianPercent != null;
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
