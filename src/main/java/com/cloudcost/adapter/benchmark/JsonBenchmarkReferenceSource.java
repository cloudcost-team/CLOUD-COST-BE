package com.cloudcost.adapter.benchmark;

import com.cloudcost.application.port.BenchmarkReferenceSource;
import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
            BenchmarkReference.Spend spend = ref.spend();
            if (spend.minUsd() == null || spend.maxUsd() == null || spend.minUsd().compareTo(spend.maxUsd()) > 0) {
                throw new IllegalStateException("spend에 minUsd·maxUsd가 있어야 하고 minUsd ≤ maxUsd여야 합니다: " + resource);
            }
            if (ref.oversized().percentiles().stream()
                .anyMatch(p -> p == null || p.percentile() == null || p.ratioPercent() == null)) {
                throw new IllegalStateException("percentiles 항목마다 percentile·ratioPercent가 있어야 합니다: " + resource);
            }
            return ref;
        } catch (IOException e) {
            throw new UncheckedIOException("벤치마킹 기준 파일을 읽지 못했습니다: " + resource, e);
        }
    }

    @Override
    public BenchmarkReference reference() {
        return reference;
    }
}
