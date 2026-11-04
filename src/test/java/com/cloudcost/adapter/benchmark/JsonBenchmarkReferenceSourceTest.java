package com.cloudcost.adapter.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.benchmark.BenchmarkReference;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class JsonBenchmarkReferenceSourceTest {

    @Test
    void 기본_파일은_Azure_SpendArk_Flexera_기준을_담고_백분위_표는_비어_있다() {
        BenchmarkReference ref = JsonBenchmarkReferenceSource.read(new ClassPathResource("benchmark/reference.json"));

        assertThat(ref.oversized().sampleSize()).isEqualTo(2607);
        assertThat(ref.oversized().averagePercent()).isEqualByComparingTo("42");
        assertThat(ref.oversized().medianPercent()).isEqualByComparingTo("35");
        assertThat(ref.oversized().percentiles()).isEmpty();
        assertThat(ref.waste().averagePercent()).isEqualByComparingTo("29");
        assertThat(ref.spend().minUsd()).isEqualByComparingTo("100");
        assertThat(ref.spend().maxUsd()).isEqualByComparingTo("500");
    }

    @Test
    void 백분위_표가_있는_파일로_바꿀_수_있고_항목이_빠지면_시작하지_않는다() {
        String json = """
            {"oversized":{"source":"x","description":"d","sampleSize":10,"averagePercent":40,"medianPercent":30,
              "percentiles":[{"percentile":50,"ratioPercent":30}]},
             "waste":{"source":"w","description":"d","averagePercent":20},
             "spend":{"source":"s","description":"d","minUsd":50,"maxUsd":300}}
            """;
        BenchmarkReference ref = JsonBenchmarkReferenceSource.read(
            new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(ref.oversized().percentiles()).hasSize(1);

        assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(
            new ByteArrayResource("{\"oversized\":{}}".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(new ByteArrayResource(
            json.replace("\"minUsd\":50,", "").getBytes(StandardCharsets.UTF_8))))
            .as("spend 범위가 빠지면 조회 때 500이 아니라 시작할 때 실패").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(new ByteArrayResource(
            json.replace("\"minUsd\":50", "\"minUsd\":900").getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(new ByteArrayResource(
            json.replace(",\"ratioPercent\":30", "").getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 백분위_표는_0에서_100_사이이고_오름차순이어야_시작한다() {
        String base = """
            {"oversized":{"source":"x","description":"d","sampleSize":10,"averagePercent":40,"medianPercent":30,
              "percentiles":%s},
             "waste":{"source":"w","description":"d","averagePercent":20},
             "spend":{"source":"s","description":"d","minUsd":50,"maxUsd":300}}
            """;
        String ok = "[{\"percentile\":25,\"ratioPercent\":10},{\"percentile\":75,\"ratioPercent\":50}]";
        assertThat(JsonBenchmarkReferenceSource.read(resource(base.formatted(ok))).oversized().percentiles()).hasSize(2);

        for (String bad : new String[] {
            "[{\"percentile\":120,\"ratioPercent\":10}]",
            "[{\"percentile\":50,\"ratioPercent\":-1}]",
            "[{\"percentile\":75,\"ratioPercent\":10},{\"percentile\":25,\"ratioPercent\":50}]",
            "[{\"percentile\":25,\"ratioPercent\":50},{\"percentile\":75,\"ratioPercent\":10}]"}) {
            assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(resource(base.formatted(bad))))
                .as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    private static ByteArrayResource resource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }
}
