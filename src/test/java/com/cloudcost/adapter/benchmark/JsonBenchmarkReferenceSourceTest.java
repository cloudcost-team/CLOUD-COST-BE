package com.cloudcost.adapter.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.benchmark.BenchmarkReference;
import com.cloudcost.domain.benchmark.BenchmarkReference.SizeBand;
import com.cloudcost.domain.benchmark.BenchmarkReference.SpendBand;
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
        {"source":"s","description":"d","bands":[{"minServers":1,"maxServers":2,"sampleSize":10,"medianVcpuHours":1440,
         "percentiles":[{"percentile":25,"vcpuHours":500},{"percentile":75,"vcpuHours":2880}]}]}""";
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
        assertThat(ref.spend().bands()).extracting(SpendBand::key).containsExactly("1-2", "3-20");
        SpendBand spendSmall = ref.spend().bandFor(1).orElseThrow();
        assertThat(spendSmall.sampleSize()).isEqualTo(1446);
        assertThat(spendSmall.medianVcpuHours()).as("2vCPU 1대를 한 달 내내").isEqualByComparingTo("1440.0");
        assertThat(spendSmall.percentiles()).hasSize(19);
        assertThat(ref.spend().bandFor(20).orElseThrow().sampleSize()).isEqualTo(2673);
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
    void 월_지출_사용량_표는_25_75번째를_포함하고_오름차순이어야_시작한다() {
        assertThat(readSpend(SPEND_OK).spend().band("1-2").orElseThrow().hasData()).isTrue();
        String head = "{\"source\":\"s\",\"description\":\"d\",\"bands\":";
        String[] bad = {
            head + "[]}",
            head + "[{\"minServers\":3,\"maxServers\":2}]}",
            head + "[{\"minServers\":1,\"maxServers\":2,\"sampleSize\":10}]}",
            head + "[{\"minServers\":1,\"maxServers\":2,\"sampleSize\":10,\"medianVcpuHours\":1,"
                + "\"percentiles\":[{\"percentile\":50,\"vcpuHours\":1}]}]}",
            head + "[{\"minServers\":1,\"maxServers\":2,\"sampleSize\":10,\"medianVcpuHours\":1,"
                + "\"percentiles\":[{\"percentile\":25,\"vcpuHours\":9},{\"percentile\":75,\"vcpuHours\":1}]}]}",
            head + "[{\"minServers\":1,\"maxServers\":2,\"sampleSize\":10,\"medianVcpuHours\":1,"
                + "\"percentiles\":[{\"percentile\":25,\"vcpuHours\":-1},{\"percentile\":75,\"vcpuHours\":1}]}]}",
            "{\"source\":\"s\",\"description\":\"d\",\"minUsd\":100,\"maxUsd\":500}",
        };
        for (String b : bad) {
            assertThatThrownBy(() -> readSpend(b)).as(b).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 백분위_표는_0에서_100_사이이고_오름차순이어야_시작한다() {
        String prefix = ",\"sampleSize\":10,\"averagePercent\":40,\"medianPercent\":30,\"percentiles\":";
        for (String table : new String[] {
            "[{\"percentile\":120,\"ratioPercent\":10}]",
            "[{\"percentile\":50,\"ratioPercent\":-1}]",
            "[{\"percentile\":75,\"ratioPercent\":10},{\"percentile\":25,\"ratioPercent\":50}]",
            "[{\"percentile\":25,\"ratioPercent\":50},{\"percentile\":75,\"ratioPercent\":10}]"}) {
            assertThatThrownBy(() -> read("[" + band(1, 2, prefix + table) + "]"))
                .as(table).isInstanceOf(IllegalStateException.class);
        }
    }
}
