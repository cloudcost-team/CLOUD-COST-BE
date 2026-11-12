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
            validateSpendBands(ref.spend().bands(), resource);
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
     * spend 구간도 1대 이상, 겹치지 않고 작은 순서. 숫자가 있는 구간은 중앙값과 백분위 표가 함께 있고, 표는 백분위 0~100·사용량 0 이상이며
     * 순서대로 줄지 않고 일반 범위에 쓰는 25·75번째 백분위를 포함한다.
     */
    private static void validateSpendBands(List<BenchmarkReference.SpendBand> bands, Resource resource) {
        if (bands.isEmpty()) {
            throw new IllegalStateException("spend.bands에 서버 대수 구간이 하나 이상 있어야 합니다: " + resource);
        }
        BenchmarkReference.SpendBand prevBand = null;
        for (BenchmarkReference.SpendBand band : bands) {
            if (band == null || band.minServers() < 1 || band.minServers() > band.maxServers()
                || (prevBand != null && band.minServers() <= prevBand.maxServers())) {
                throw new IllegalStateException("spend.bands는 1대 이상이고 겹치지 않게 작은 순서여야 합니다: " + resource);
            }
            boolean anyNumber = band.sampleSize() > 0 || band.medianVcpuHours() != null || !band.percentiles().isEmpty();
            if (anyNumber && !band.hasData()) {
                throw new IllegalStateException(band.key() + " spend 구간은 sampleSize·medianVcpuHours·percentiles가 모두 있거나 모두 없어야 합니다: " + resource);
            }
            List<BenchmarkReference.UsagePercentile> table = band.percentiles();
            for (int i = 0; i < table.size(); i++) {
                BenchmarkReference.UsagePercentile p = table.get(i);
                if (p == null || p.percentile() == null || p.vcpuHours() == null) {
                    throw new IllegalStateException("spend percentiles 항목마다 percentile·vcpuHours가 있어야 합니다: " + resource);
                }
                BenchmarkReference.UsagePercentile prev = i == 0 ? null : table.get(i - 1);
                if (!inPercentRange(p.percentile()) || p.vcpuHours().signum() < 0
                    || (prev != null && (p.percentile().compareTo(prev.percentile()) < 0
                        || p.vcpuHours().compareTo(prev.vcpuHours()) < 0))) {
                    throw new IllegalStateException("spend percentiles는 백분위 0~100, 사용량 0 이상이고 오름차순이어야 합니다: " + resource);
                }
            }
            if (band.hasData() && (!hasPercentile(table, SpendBenchmark.LOW_PERCENTILE)
                || !hasPercentile(table, SpendBenchmark.HIGH_PERCENTILE))) {
                throw new IllegalStateException(band.key() + " spend 구간의 percentiles에 25·75번째 백분위가 있어야 합니다: " + resource);
            }
            prevBand = band;
        }
    }

    private static boolean hasPercentile(List<BenchmarkReference.UsagePercentile> table, BigDecimal percentile) {
        return table.stream().anyMatch(p -> p.percentile().compareTo(percentile) == 0);
    }

    private static boolean inPercentRange(BigDecimal value) {
        return value.signum() >= 0 && value.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    @Override
    public BenchmarkReference reference() {
        return reference;
    }
}
