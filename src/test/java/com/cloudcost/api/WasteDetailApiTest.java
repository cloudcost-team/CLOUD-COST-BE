package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S07-2 추천 상세 (와이어프레임): 10초 요약 3줄 · 메모리 경고 · 다음 후보 · 최근 14일 CPU 그래프와 기준선.
 * NOW = 2026-10-08T03:00Z, 판단 구간 = [2026-09-24T03:00Z, 2026-10-08T03:00Z) 336시간.
 */
class WasteDetailApiTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T03:00:00Z");
    private static final Instant PEAK = WINDOW_START.plusSeconds(3600L * 120);
    private static final long MB = 1024L * 1024L;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
        priceCatalog.hourlyByType.put("t3.large", new BigDecimal("0.104"));
        priceCatalog.hourlyByType.put("c5.large", new BigDecimal("0.096"));

        // api-server: 평소 CPU 6%(최대 8%), 9/29 03시(UTC)에 한 번 최대 23%
        aws.instances.add(Fixtures.instance("i-api", "m5.large", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-api", WINDOW_START, 120, 6, 8);
        aws.addCpuHours("i-api", PEAK, 1, 9, 23);
        aws.addCpuHours("i-api", PEAK.plusSeconds(3600), 215, 6, 8);
        aws.addNetworkHours("i-api", WINDOW_START, 336, 5 * MB, 5 * MB);
        // batch: 유휴
        aws.instances.add(Fixtures.instance("i-batch", "c5.large", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-batch", WINDOW_START, 336, 0.4, 1.5);
        aws.addNetworkHours("i-batch", WINDOW_START, 336, 50, 50);
    }

    private JsonNode item(String currency, String recommendationId) throws Exception {
        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/waste?currency=" + currency)
                .andExpect(status().isOk()));
        for (JsonNode item : body.at("/items")) {
            if (item.at("/recommendationId").asText().equals(recommendationId)) {
                return item;
            }
        }
        throw new AssertionError(recommendationId + " 없음: " + body);
    }

    @Test
    void 과다_사양은_10초_요약_3줄과_메모리_경고와_다음_후보를_준다() throws Exception {
        JsonNode api = item("USD", "i-api:OVERSIZED");

        assertThat(api.at("/summary/0/title").asText()).isEqualTo("거의 안 쓰고 있어요");
        assertThat(api.at("/summary/0/detail").asText()).isEqualTo("14일 평균 CPU 6%, 가장 높을 때도 23%예요");
        assertThat(api.at("/summary/1/title").asText()).isEqualTo("줄여도 넉넉해요");
        assertThat(api.at("/summary/1/detail").asText()).isEqualTo("바꿔도 예상 사용률 6%, 기본 성능(20%) 안이에요");
        assertThat(api.at("/summary/2/title").asText()).isEqualTo("조건을 만족하는 것 중 가장 싸요");
        assertThat(api.at("/summary/2/detail").asText()).isEqualTo("시간당 $0.052 (다음 후보 t3.large $0.104)");
        assertThat(api.at("/summaryNote").asText())
                .isEqualTo("메모리(8 → 4 GiB)는 확인하지 못했어요. 바꾸기 전에 서버에서 직접 확인해 주세요.");

        assertThat(api.at("/basis/projectedCpuP95").decimalValue()).isEqualByComparingTo("6.0");
        assertThat(api.at("/basis/targetBaselinePercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(api.at("/basis/nextCandidate/instanceType").asText()).isEqualTo("t3.large");
        assertThat(api.at("/basis/nextCandidate/hourlyCost").decimalValue()).isEqualByComparingTo("0.104");
    }

    @Test
    void 그래프는_시간별_CPU와_가장_높았던_시간과_추천_타입_기본_성능_기준선을_준다() throws Exception {
        JsonNode chart = item("USD", "i-api:OVERSIZED").at("/cpuChart");

        assertThat(chart.at("/points").size()).as("14일 × 24시간").isEqualTo(336);
        assertThat(chart.at("/points/0/hour").asText()).isEqualTo("2026-09-24T03:00:00Z");
        assertThat(chart.at("/points/0/avg").decimalValue()).isEqualByComparingTo("6");
        assertThat(chart.at("/points/120/max").decimalValue()).isEqualByComparingTo("23");
        assertThat(chart.at("/max").decimalValue()).isEqualByComparingTo("23");
        assertThat(chart.at("/maxAt").asText()).isEqualTo("2026-09-29T03:00:00Z");
        assertThat(chart.at("/baselinePercent").decimalValue()).isEqualByComparingTo("20");
        assertThat(chart.at("/baselineLabel").asText()).isEqualTo("t3.medium 기본 성능 20%");
    }

    @Test
    void 원화_요약은_원화로_적는다() throws Exception {
        String detail = item("KRW", "i-api:OVERSIZED").at("/summary/2/detail").asText();

        assertThat(detail).matches("시간당 ₩[0-9,]+(\\.[0-9])? \\(다음 후보 t3\\.large ₩[0-9,]+(\\.[0-9])?\\)");
    }

    @Test
    void 유휴는_기준선_없이_요약과_그래프를_준다() throws Exception {
        JsonNode idle = item("USD", "i-batch:IDLE");

        assertThat(idle.at("/summary/0/detail").asText()).isEqualTo("14일 동안 CPU가 가장 높을 때도 2%예요");
        assertThat(idle.at("/summary/1/title").asText()).isEqualTo("네트워크도 거의 없어요");
        assertThat(idle.at("/summary/1/detail").asText()).isEqualTo("하루 평균 입출력이 0.1MB 미만이에요");
        assertThat(idle.at("/summary/2/detail").asText()).isEqualTo("매달 $70.08 절감 예상 (추정)");
        assertThat(idle.at("/summaryNote").isNull()).isTrue();
        assertThat(idle.at("/basis").isNull()).isTrue();
        assertThat(idle.at("/cpuChart/points").size()).isEqualTo(336);
        assertThat(idle.at("/cpuChart/baselinePercent").isNull()).isTrue();
        assertThat(idle.at("/cpuChart/baselineLabel").isNull()).isTrue();
    }
}
