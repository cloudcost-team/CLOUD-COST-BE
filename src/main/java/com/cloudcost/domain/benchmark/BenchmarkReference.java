package com.cloudcost.domain.benchmark;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 팀 벤치마킹 비교 기준 (이슈 #33). 외부 자료의 숫자라 코드가 아니라 교체 가능한 리소스 파일에서 읽는다 (ADR-013).
 *
 * @param oversized 과다 사양 서버 비율 기준 (서버 대수 구간별)
 * @param waste     전체 낭비 비율 참고값 (지금 권한으로는 우리 값을 계산할 수 없다)
 * @param spend     규모(서버 대수)별 한 달 사용량 예측 모델
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

    /**
     * 규모(서버 대수)별 한 달 사용량(vCPU-시간 = vCPU × 켜진 시간) 예측 모델. 금액이 아니라 사용량으로 두는 이유는
     * 원본에 가격이 없어서다. 우리 팀도 같은 단위로 재서 순위를 매기고, 금액 범위는 우리 팀의 vCPU-시간당 비용으로 환산한다.
     */
    public record Spend(String source, String description, SpendModel model) {
    }

    /**
     * 백분위마다 따로 학습한 선형 분위 회귀 (scripts/benchmark/AzureSpend.java).
     * ln(사용량)의 p번째 백분위 = c0 + c1·ln(servers) + c2·ln(servers)².
     *
     * @param minServers 학습한 서버 대수 범위 (이 밖은 예측하지 않는다)
     * @param sampleSize 학습에 쓴 팀(구독) 수
     * @param quantiles  백분위 오름차순, 25·50·75 포함
     */
    public record SpendModel(
        int minServers,
        int maxServers,
        int sampleSize,
        Validation validation,
        List<QuantileCoefficients> quantiles) {

        public SpendModel {
            quantiles = quantiles == null ? List.of() : List.copyOf(quantiles);
        }

        public boolean covers(int servers) {
            return servers >= minServers && servers <= maxServers;
        }

        /**
         * 서버 n대 팀의 사용량 백분위 표 (vCPU-시간, 소수 첫째 자리). 백분위끼리 순서가 뒤집히면 정렬해 바로잡는다 (rearrangement).
         * 학습 범위 밖이면 empty.
         */
        public Optional<List<UsagePercentile>> table(int servers) {
            if (!covers(servers)) {
                return Optional.empty();
            }
            double x = Math.log(servers);
            double[] values = quantiles.stream()
                .mapToDouble(q -> Math.exp(q.c(0) + q.c(1) * x + q.c(2) * x * x))
                .sorted()
                .toArray();
            List<UsagePercentile> table = new java.util.ArrayList<>();
            for (int i = 0; i < quantiles.size(); i++) {
                table.add(new UsagePercentile(quantiles.get(i).percentile(),
                    BigDecimal.valueOf(values[i]).setScale(1, java.math.RoundingMode.HALF_UP)));
            }
            return Optional.of(List.copyOf(table));
        }
    }

    /**
     * 학습용·시험용(8:2)으로 나눠 잰 검증 결과.
     *
     * @param pinballLoss               시험용 pinball loss (19개 백분위 평균, ln 공간). 작을수록 좋다
     * @param baselinePinballLoss       같은 시험용에서 기존 방식(baseline)의 pinball loss
     * @param middleHalfCoveragePercent 예측한 25~75번째 백분위 범위에 실제 값이 들어온 비율 (잘 맞으면 50% 근처)
     */
    public record Validation(
        int trainSize,
        int testSize,
        BigDecimal pinballLoss,
        BigDecimal baselinePinballLoss,
        String baseline,
        BigDecimal middleHalfCoveragePercent) {

        /** 기존 방식보다 pinball loss가 줄어든 비율 (%, 소수 첫째 자리) */
        public BigDecimal improvementPercent() {
            return baselinePinballLoss.subtract(pinballLoss).multiply(BigDecimal.valueOf(100))
                .divide(baselinePinballLoss, 1, java.math.RoundingMode.HALF_UP);
        }
    }

    /** p번째 백분위 모델의 계수 [c0, c1, c2] */
    public record QuantileCoefficients(BigDecimal percentile, List<BigDecimal> coefficients) {

        public QuantileCoefficients {
            coefficients = coefficients == null ? List.of() : List.copyOf(coefficients);
        }

        double c(int i) {
            return coefficients.get(i).doubleValue();
        }
    }

    /** 팀의 percentile%가 한 달 사용량 vcpuHours 이하 */
    public record UsagePercentile(BigDecimal percentile, BigDecimal vcpuHours) {
    }
}
