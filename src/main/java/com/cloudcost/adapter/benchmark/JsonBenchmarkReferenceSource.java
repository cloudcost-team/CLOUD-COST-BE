package com.cloudcost.adapter.benchmark;

import com.cloudcost.application.port.BenchmarkReferenceSource;
import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.SpendBenchmark;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 비교 기준 JSON을 시작할 때 한 번 읽는다. 기본은 classpath:benchmark/reference.json이고
 * CCI_BENCHMARK_REFERENCE(cci.benchmark.reference)로 다른 파일을 지정할 수 있다.
 */
@Component
class JsonBenchmarkReferenceSource implements BenchmarkReferenceSource {

    private final BenchmarkReference reference;

    JsonBenchmarkReferenceSource(
            ResourceLoader loader,
            @Value("${cci.benchmark.reference:classpath:benchmark/reference.json}") String location) {
        this.reference = read(loader.getResource(location));
    }

    static BenchmarkReference read(Resource resource) {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = resource.getInputStream()) {
            BenchmarkReference ref = mapper.readValue(in, BenchmarkReference.class);
            if (ref.oversized() == null || ref.waste() == null || ref.spend() == null) {
                throw new IllegalStateException("벤치마킹 기준 파일에 oversized·waste·spend가 모두 있어야 합니다: " + resource);
            }
            validateBands(ref.oversized().bands(), resource);
            validateSpendModel(ref.spend().model(), resource);
            return ref;
        } catch (IOException e) {
            throw new UncheckedIOException("벤치마킹 기준 파일을 읽지 못했습니다: " + resource, e);
        }
    }

    /**
     * 구간은 1대 이상, 겹치지 않고 작은 순서. 숫자가 있는 구간은 평균·중앙값이 함께 있고, 백분위 표는 0~100이며 순서대로 줄지 않는다
     * (보간이 틀어지지 않게). 아직 계산하지 않은 구간은 sampleSize 0, 평균·중앙값 null, 표 없음이다.
     */
    private static void validateBands(List<BenchmarkReference.SizeBand> bands, Resource resource) {
        if (bands.isEmpty()) {
            throw new IllegalStateException("oversized.bands에 서버 대수 구간이 하나 이상 있어야 합니다: " + resource);
        }
        BenchmarkReference.SizeBand prevBand = null;
        for (BenchmarkReference.SizeBand band : bands) {
            if (band == null || band.minServers() < 1 || band.minServers() > band.maxServers()
                || (prevBand != null && band.minServers() <= prevBand.maxServers())) {
                throw new IllegalStateException("oversized.bands는 1대 이상이고 겹치지 않게 작은 순서여야 합니다: " + resource);
            }
            boolean anyNumber = band.averagePercent() != null || band.medianPercent() != null || band.sampleSize() > 0;
            if (anyNumber && !band.hasData()) {
                throw new IllegalStateException(band.key() + " 구간은 sampleSize·averagePercent·medianPercent가 모두 있거나 모두 없어야 합니다: " + resource);
            }
            if (!band.hasData() && !band.percentiles().isEmpty()) {
                throw new IllegalStateException(band.key() + " 구간은 숫자 없이 백분위 표만 있을 수 없습니다: " + resource);
            }
            List<BenchmarkReference.Percentile> percentiles = band.percentiles();
            for (int i = 0; i < percentiles.size(); i++) {
                BenchmarkReference.Percentile p = percentiles.get(i);
                if (p == null || p.percentile() == null || p.ratioPercent() == null) {
                    throw new IllegalStateException("percentiles 항목마다 percentile·ratioPercent가 있어야 합니다: " + resource);
                }
                BenchmarkReference.Percentile prev = i == 0 ? null : percentiles.get(i - 1);
                if (!inPercentRange(p.percentile()) || !inPercentRange(p.ratioPercent())
                    || (prev != null && (p.percentile().compareTo(prev.percentile()) < 0
                        || p.ratioPercent().compareTo(prev.ratioPercent()) < 0))) {
                    throw new IllegalStateException("percentiles의 percentile·ratioPercent는 0~100이고 오름차순이어야 합니다: " + resource);
                }
            }
            prevBand = band;
        }
    }

    /**
     * spend 모델이 가질 수 있는 가장 큰 서버 대수. reference.json의 학습 범위(1~50대)에 맞춘 값이라 학습 범위를 넓히면 같이 올린다.
     * 상한이 없으면 아래 검사 루프가 maxServers번 돌아 큰 값이 들어온 파일에서 시작이 끝나지 않는다.
     */
    static final int MAX_MODEL_SERVERS = 50;

    /**
     * spend 모델은 서버 대수 범위(1대 이상, min ≤ max)와 검증 결과가 있고, 백분위별 계수 3개씩이 백분위 오름차순으로 있으며
     * 일반 범위·중앙값에 쓰는 25·50·75번째를 포함해야 한다. 학습 범위 모든 서버 대수에서 예측값이 유한해야 한다.
     */
    private static void validateSpendModel(BenchmarkReference.SpendModel model, Resource resource) {
        if (model == null || model.minServers() < 1 || model.minServers() > model.maxServers()
            || model.maxServers() > MAX_MODEL_SERVERS || model.sampleSize() <= 0
            || model.validation() == null || model.validation().pinballLoss() == null
            || model.validation().baselinePinballLoss() == null || model.validation().baselinePinballLoss().signum() <= 0
            || model.validation().middleHalfCoveragePercent() == null) {
            throw new IllegalStateException("spend.model에 서버 대수 범위(1~" + MAX_MODEL_SERVERS
                + "대)·sampleSize·검증 결과가 있어야 합니다: " + resource);
        }
        List<BenchmarkReference.QuantileCoefficients> qs = model.quantiles();
        for (int i = 0; i < qs.size(); i++) {
            BenchmarkReference.QuantileCoefficients q = qs.get(i);
            if (q == null || q.percentile() == null || q.coefficients().size() != 3
                || q.coefficients().stream().anyMatch(java.util.Objects::isNull) || !inPercentRange(q.percentile())
                || (i > 0 && q.percentile().compareTo(qs.get(i - 1).percentile()) <= 0)) {
                throw new IllegalStateException("spend.model.quantiles는 백분위 0~100 오름차순이고 계수가 3개씩이어야 합니다: " + resource);
            }
        }
        for (BigDecimal needed : List.of(SpendBenchmark.LOW_PERCENTILE, SpendBenchmark.MEDIAN_PERCENTILE,
            SpendBenchmark.HIGH_PERCENTILE)) {
            if (qs.stream().noneMatch(q -> q.percentile().compareTo(needed) == 0)) {
                throw new IllegalStateException("spend.model.quantiles에 25·50·75번째 백분위가 있어야 합니다: " + resource);
            }
        }
        for (int servers = model.minServers(); servers <= model.maxServers(); servers++) {
            boolean finite = model.table(servers).orElseThrow().stream()
                .allMatch(p -> p.vcpuHours().signum() > 0);
            if (!finite) {
                throw new IllegalStateException("spend.model이 서버 " + servers + "대에서 0 이하를 예측합니다: " + resource);
            }
        }
    }

    private static boolean inPercentRange(BigDecimal value) {
        return value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    @Override
    public BenchmarkReference reference() {
        return reference;
    }
}
