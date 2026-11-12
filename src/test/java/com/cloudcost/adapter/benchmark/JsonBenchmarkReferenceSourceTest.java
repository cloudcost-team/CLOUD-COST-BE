package com.cloudcost.adapter.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.SizeBand;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class JsonBenchmarkReferenceSourceTest {

    private static final String TEMPLATE = """
        {"oversized":{"source":"x","description":"d","bands":%s},
         "waste":{"source":"w","description":"d","averagePercent":20},
         "spend":%s}
        """;
    private static final String SPEND_OK = """
        {"source":"s","description":"d","model":{"minServers":1,"maxServers":50,"sampleSize":10,
         "validation":{"trainSize":8,"testSize":2,"pinballLoss":0.3,"baselinePinballLoss":0.4,"baseline":"b","middleHalfCoveragePercent":50},
         "quantiles":[{"percentile":25,"coefficients":[6,1,0]},{"percentile":50,"coefficients":[7,1,0]},
                      {"percentile":75,"coefficients":[8,1,0]}]}}""";
    private static final String EMPTY_1_2 =
        "{\"minServers\":1,\"maxServers\":2,\"sampleSize\":0,\"averagePercent\":null,\"medianPercent\":null,\"percentiles\":[]}";

    private static BenchmarkReference read(String bands) {
        return JsonBenchmarkReferenceSource.read(
            new ByteArrayResource(TEMPLATE.formatted(bands, SPEND_OK).getBytes(StandardCharsets.UTF_8)));
    }

    private static BenchmarkReference readSpend(String spend) {
        return JsonBenchmarkReferenceSource.read(new ByteArrayResource(
            TEMPLATE.formatted("[" + EMPTY_1_2 + "]", spend).getBytes(StandardCharsets.UTF_8)));
    }

    private static String band(int min, int max, String extra) {
        return "{\"minServers\":" + min + ",\"maxServers\":" + max + extra + "}";
    }

    @Test
    void 기본_파일은_Azure_원본에서_계산한_1_2대와_3_20대_분포를_담는다() {
        BenchmarkReference ref = JsonBenchmarkReferenceSource.read(new ClassPathResource("benchmark/reference.json"));

        assertThat(ref.oversized().source()).isEqualTo("Azure Public Dataset V2");
        assertThat(ref.oversized().bands()).extracting(SizeBand::key).containsExactly("1-2", "3-20");
        SizeBand small = ref.oversized().bandFor(2).orElseThrow();
        assertThat(small.label()).isEqualTo("서버 1~2대");
        assertThat(small.sampleSize()).isEqualTo(1473);
        assertThat(small.averagePercent()).isEqualByComparingTo("47");
        assertThat(small.medianPercent()).isEqualByComparingTo("50");
        assertThat(small.percentiles()).hasSize(19);
        SizeBand mid = ref.oversized().bandFor(3).orElseThrow();
        assertThat(mid.sampleSize()).isEqualTo(2622);
        assertThat(mid.averagePercent()).isEqualByComparingTo("42");
        assertThat(mid.medianPercent()).isEqualByComparingTo("33");
        assertThat(ref.oversized().bandFor(21)).as("어느 구간에도 안 듦").isEmpty();
        assertThat(ref.waste().averagePercent()).isEqualByComparingTo("29");
        assertThat(ref.spend().source()).isEqualTo("Azure Public Dataset V2");
        BenchmarkReference.SpendModel model = ref.spend().model();
        assertThat(model.sampleSize()).isEqualTo(5452);
        assertThat(model.quantiles()).hasSize(19);
        assertThat(model.validation().improvementPercent()).isEqualByComparingTo("15.3");
        assertThat(model.table(1).orElseThrow()).filteredOn(p -> p.percentile().intValue() == 50)
            .extracting(BenchmarkReference.UsagePercentile::vcpuHours).as("2vCPU 1대를 한 달 내내")
            .allSatisfy(v -> assertThat(v).isEqualByComparingTo("1440.0"));
        assertThat(model.table(51)).as("학습 범위 밖").isEmpty();
    }

    @Test
    void 구간마다_숫자와_백분위_표를_넣을_수_있고_형식이_틀리면_시작하지_않는다() {
        String ok = "[" + EMPTY_1_2 + ","
            + band(3, 20, ",\"sampleSize\":10,\"averagePercent\":40,\"medianPercent\":30,"
                + "\"percentiles\":[{\"percentile\":25,\"ratioPercent\":10},{\"percentile\":75,\"ratioPercent\":50}]") + "]";
        assertThat(read(ok).oversized().band("3-20").orElseThrow().percentiles()).hasSize(2);

        String[] bad = {
            "[]",
            "[" + band(3, 2, "") + "]",
            "[" + band(0, 2, "") + "]",
            "[" + band(1, 5, "") + "," + band(3, 20, "") + "]",
            "[" + band(3, 20, "") + "," + band(1, 2, "") + "]",
            "[" + band(1, 2, ",\"sampleSize\":10,\"averagePercent\":40") + "]",
            "[" + band(1, 2, ",\"percentiles\":[{\"percentile\":50,\"ratioPercent\":30}]") + "]",
            "[" + band(1, 2, ",\"sampleSize\":10,\"averagePercent\":40,\"medianPercent\":30,"
                + "\"percentiles\":[{\"percentile\":50}]") + "]",
        };
        for (String b : bad) {
            assertThatThrownBy(() -> read(b)).as(b).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> JsonBenchmarkReferenceSource.read(
            new ByteArrayResource("{\"oversized\":{}}".getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 월_지출_모델은_25_50_75번째와_계수_3개와_검증_결과가_있어야_시작한다() {
        assertThat(readSpend(SPEND_OK).spend().model().table(2)).isPresent();
        String[] bad = {
            SPEND_OK.replace("{\"percentile\":50,\"coefficients\":[7,1,0]},", ""),
            SPEND_OK.replace("[7,1,0]", "[7,1]"),
            SPEND_OK.replace("\"percentile\":75", "\"percentile\":20"),
            SPEND_OK.replace("\"minServers\":1,\"maxServers\":50", "\"minServers\":5,\"maxServers\":2"),
            SPEND_OK.replace("\"maxServers\":50", "\"maxServers\":2147483647"),
            SPEND_OK.replace("\"pinballLoss\":0.3,", ""),
            "{\"source\":\"s\",\"description\":\"d\",\"minUsd\":100,\"maxUsd\":500}",
        };
        for (String b : bad) {
            assertThatThrownBy(() -> readSpend(b)).as(b).isInstanceOf(IllegalStateException.class);
        }
    }
}
