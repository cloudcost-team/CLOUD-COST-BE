package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 팀 벤치마킹 (#33). NOW = 2026-10-08 12:00 KST. 서버 구성은 SimulationApiTest와 같다 → 월말 예상 $197.904.
 * i-api(P95 6%)·i-batch(P95 0.4%)는 과다 사양, i-web(P95 50%)은 적정 → 3대 중 2대 = 66.7%.
 */
class BenchmarkApiTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T03:00:00Z");

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
        priceCatalog.hourlyByType.put("c5.large", new BigDecimal("0.096"));
    }

    private void server(String id, String type, double cpuAvg, double cpuMax, long networkPerHour) {
        aws.instances.add(Fixtures.instance(id, type, "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours(id, WINDOW_START, 336, cpuAvg, cpuMax);
        aws.addNetworkHours(id, WINDOW_START, 336, networkPerHour / 2, networkPerHour / 2);
    }

    private void threeServers() {
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-batch", "c5.large", 0.4, 1.5, 100);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);
    }

    private JsonNode benchmark(String query) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/benchmark?currency=USD" + query)
                .andExpect(status().isOk()));
    }

    @Test
    void 과다_사양_비율과_서버별_판정_낭비_비율_불가_월_지출_위치를_준다() throws Exception {
        threeServers();
        JsonNode body = benchmark("");

        assertThat(body.at("/status").asText()).isEqualTo("OK");
        JsonNode oversized = body.at("/oversizedRatio");
        assertThat(oversized.at("/status").asText()).isEqualTo("OK");
        assertThat(oversized.at("/ours/judgedCount").asInt()).isEqualTo(3);
        assertThat(oversized.at("/ours/oversizedCount").asInt()).isEqualTo(2);
        assertThat(oversized.at("/ours/ratio").decimalValue()).isEqualByComparingTo("66.7");
        assertThat(oversized.at("/reference/source").asText()).isEqualTo("Azure Public Dataset V2");
        assertThat(oversized.at("/reference/sampleSize").asInt()).isEqualTo(2607);
        assertThat(oversized.at("/reference/averageRatio").decimalValue()).isEqualByComparingTo("42");
        assertThat(oversized.at("/reference/medianRatio").decimalValue()).isEqualByComparingTo("35");
        assertThat(oversized.at("/reference/percentileAvailable").asBoolean()).isFalse();
        assertThat(oversized.at("/moreEfficientTeamsPercent").isNull()).as("백분위 표가 없으면 null").isTrue();
        assertThat(oversized.at("/servers").findValuesAsText("instanceId")).containsExactly("i-batch", "i-api", "i-web");
        assertThat(oversized.at("/servers/2/verdict").asText()).isEqualTo("적정");
        assertThat(oversized.at("/afterSimulation").isNull()).isTrue();

        assertThat(body.at("/wasteRatio/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.at("/wasteRatio/reason").asText()).contains("지금 연결 권한");
        assertThat(body.at("/wasteRatio/referenceAverage").decimalValue()).isEqualByComparingTo("29");

        JsonNode spend = body.at("/monthlySpend");
        assertThat(spend.at("/status").asText()).isEqualTo("OK");
        assertThat(spend.at("/monthlyCost").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(spend.at("/position").asText()).isEqualTo("WITHIN");
        assertThat(spend.at("/range/minUsd").decimalValue()).isEqualByComparingTo("100");
        assertThat(spend.at("/afterSimulation").isNull()).isTrue();
        assertThat(body.at("/notes").toString()).contains("우리보다 효율적인 팀");
    }

    @Test
    void 월_지출에서_뺀_단가_모르는_서버를_안내에_보여_준다() throws Exception {
        threeServers();
        server("i-x", "x9.unknown", 50, 90, 50L * 1024 * 1024);

        JsonNode body = benchmark("");

        assertThat(body.at("/notes").toString()).as("대시보드 월말 예상에서 뺀 서버 안내").contains("단가를 찾지 못한 인스턴스 1개");
        java.util.List<String> notes = new java.util.ArrayList<>();
        body.at("/notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).as("겹치는 문구는 한 번만").doesNotHaveDuplicates();
    }

    @Test
    void 추천을_주면_적용_후_위치도_준다() throws Exception {
        threeServers();
        JsonNode body = benchmark("&recommendationIds=i-batch:IDLE,i-api:OVERSIZED");

        assertThat(body.at("/appliedRecommendationIds").size()).isEqualTo(2);
        JsonNode after = body.at("/oversizedRatio/afterSimulation");
        assertThat(after.at("/judgedCount").asInt()).as("중지한 batch는 빠진다").isEqualTo(2);
        assertThat(after.at("/oversizedCount").asInt()).isZero();
        assertThat(body.at("/monthlySpend/afterSimulation/monthlyCost").decimalValue())
                .as("197.904 − 118.26").isEqualByComparingTo("79.64");
        assertThat(body.at("/monthlySpend/afterSimulation/position").asText()).isEqualTo("BELOW");

        getJson("/api/v1/aws/accounts/" + accountId + "/benchmark?recommendationIds=i-web:IDLE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_RECOMMENDATION"));
    }

    @Test
    void 판단한_서버가_3대_미만이면_과다_사양만_비교하지_않는다() throws Exception {
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);

        JsonNode body = benchmark("");

        assertThat(body.at("/status").asText()).isEqualTo("OK");
        assertThat(body.at("/oversizedRatio/status").asText()).isEqualTo("NOT_COMPARABLE");
        assertThat(body.at("/oversizedRatio/ours/ratio").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(body.at("/oversizedRatio/servers").size()).isEqualTo(2);
        assertThat(body.at("/monthlySpend/status").asText()).isEqualTo("OK");
    }

    @Test
    void 오래_꺼_둔_서버는_낭비_탐지처럼_판단하지_않는다() throws Exception {
        threeServers();
        // 9/24~9/29만 켜졌다가 지금 중지 → 낭비 탐지가 판단하지 않는 서버
        aws.instances.add(Fixtures.instance("i-old", "m5.large", "stopped", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-old", WINDOW_START, 5 * 24, 3, 5);

        JsonNode body = benchmark("");
        JsonNode waste = body(getJson("/api/v1/aws/accounts/" + accountId + "/waste").andExpect(status().isOk()));

        assertThat(body.at("/oversizedRatio/ours/judgedCount").asInt())
                .isEqualTo(waste.at("/judgedCount").asInt()).isEqualTo(3);
        assertThat(body.at("/oversizedRatio/servers").findValuesAsText("instanceId")).doesNotContain("i-old");
    }

    private static final String DEMO = """
        {"from":"%s","to":"2026-10-07","servers":[
          {"name":"api","instanceType":"m5.large","os":"LINUX","count":2,"schedule":"ALWAYS","usagePattern":"OVERSIZED"},
          {"name":"web","instanceType":"t3.medium","os":"LINUX","count":2,"schedule":"ALWAYS"}]}
        """;

    @Test
    void 체험_모드도_같은_규칙으로_비교하고_기록이_14일_미만이면_기록_부족이다() throws Exception {
        JsonNode body = body(mvc.perform(post("/api/v1/demo/benchmark?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(DEMO.formatted("2026-09-20")))
                .andExpect(status().isOk()));

        assertThat(body.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(body.at("/oversizedRatio/ours/judgedCount").asInt()).isEqualTo(4);
        assertThat(body.at("/oversizedRatio/ours/ratio").decimalValue()).isEqualByComparingTo("50.0");

        JsonNode shortPeriod = body(mvc.perform(post("/api/v1/demo/benchmark")
                .contentType(MediaType.APPLICATION_JSON).content(DEMO.formatted("2026-10-01")))
                .andExpect(status().isOk()));
        assertThat(shortPeriod.at("/status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(shortPeriod.at("/availableAt").asText()).isEqualTo("2026-10-15");
        assertThat(shortPeriod.at("/oversizedRatio/status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(shortPeriod.at("/oversizedRatio/ours").isNull()).isTrue();
        assertThat(shortPeriod.at("/monthlySpend/status").asText()).as("월 지출은 기록이 짧아도 비교").isEqualTo("OK");
    }
}
