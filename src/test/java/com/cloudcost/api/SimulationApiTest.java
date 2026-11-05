package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비용 시뮬레이터 (#31). NOW = 2026-10-08 12:00 KST. 세 서버가 9/24 12:00 KST부터 계속 켜져 있다 (WasteApiTest와 같은 구성).
 * 하루 비용 = (0.118 + 0.096 + 0.052) × 24 = $6.384 → 월말 예상 = 6.384 × 7 + 6.384 × 24 = $197.904.
 * 추천: i-batch:IDLE 월 $70.08, i-api:OVERSIZED 월 $48.18.
 */
class SimulationApiTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T03:00:00Z");

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
        priceCatalog.hourlyByType.put("c5.large", new BigDecimal("0.096"));
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-batch", "c5.large", 0.4, 1.5, 100);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);
    }

    private void server(String id, String type, double cpuAvg, double cpuMax, long networkPerHour) {
        aws.instances.add(Fixtures.instance(id, type, "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours(id, WINDOW_START, 336, cpuAvg, cpuMax);
        aws.addNetworkHours(id, WINDOW_START, 336, networkPerHour / 2, networkPerHour / 2);
    }

    private ResultActions simulate(String json) throws Exception {
        return call(post("/api/v1/aws/accounts/" + accountId + "/simulations?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void 고른_추천을_적용하고_트래픽_증가를_반영한_전후_비용을_준다() throws Exception {
        call(put("/api/v1/aws/accounts/" + accountId + "/budget")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100,\"currency\":\"USD\"}"))
                .andExpect(status().isOk());

        JsonNode body = body(simulate("""
                {"recommendationIds":["i-batch:IDLE","i-api:OVERSIZED","i-batch:IDLE"],"trafficGrowthPercent":20}
                """).andExpect(status().isOk()));

        assertThat(body.at("/status").asText()).isEqualTo("OK");
        assertThat(body.at("/selectedCount").asInt()).as("중복은 한 번만").isEqualTo(2);
        assertThat(body.at("/selected/0/recommendationId").asText()).isEqualTo("i-batch:IDLE");
        assertThat(body.at("/selectedMonthlySavings").decimalValue()).isEqualByComparingTo("118.26");
        assertThat(body.at("/forecastMonthEndCost").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(body.at("/before/monthlyCost").decimalValue()).as("197.904 × 1.2").isEqualByComparingTo("237.48");
        assertThat(body.at("/after/monthlyCost").decimalValue()).as("(197.904 − 118.26) × 1.2").isEqualByComparingTo("95.57");
        assertThat(body.at("/monthlySavings").decimalValue()).isEqualByComparingTo("141.91");
        assertThat(body.at("/yearlySavings").decimalValue()).isEqualByComparingTo("1702.94");
        assertThat(body.at("/before/budgetRate").decimalValue()).isEqualByComparingTo("237.5");
        assertThat(body.at("/after/budgetRate").decimalValue()).isEqualByComparingTo("95.6");
        assertThat(body.at("/budget/amount").decimalValue()).isEqualByComparingTo("100");
        assertThat(body.at("/withinBudget").asBoolean()).isTrue();
    }

    @Test
    void 추천을_고르지_않고_예산이_없으면_전후가_같고_withinBudget은_null이다() throws Exception {
        JsonNode body = body(simulate("{}").andExpect(status().isOk()));

        assertThat(body.at("/trafficGrowthPercent").asInt()).isZero();
        assertThat(body.at("/before/monthlyCost").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(body.at("/after/monthlyCost").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(body.at("/monthlySavings").decimalValue()).isEqualByComparingTo("0");
        assertThat(body.at("/before/budgetRate").isNull()).isTrue();
        assertThat(body.at("/budget").isNull()).isTrue();
        assertThat(body.at("/withinBudget").isNull()).isTrue();
    }

    @Test
    void 모르는_추천_id나_범위_밖_증가율은_400이다() throws Exception {
        simulate("{\"recommendationIds\":[\"i-web:OVERSIZED\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_RECOMMENDATION"));
        simulate("{\"trafficGrowthPercent\":101}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.trafficGrowthPercent").value("0~100 사이로 입력해 주세요"));
        simulate("{\"trafficGrowthPercent\":-1}").andExpect(status().isBadRequest());
        // 소수는 버리지 않고 거절한다 (100.9가 100으로 바뀌어 범위 검사를 지나가지 않게)
        simulate("{\"trafficGrowthPercent\":100.9}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        simulate("{\"trafficGrowthPercent\":20.5}").andExpect(status().isBadRequest());
    }

    @Test
    void 남의_연결은_404다() throws Exception {
        mvc.perform(as("someone-else", post("/api/v1/aws/accounts/" + accountId + "/simulations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void 체험_모드도_같은_계산으로_시뮬레이션한다() throws Exception {
        // 9/20~10/7: api(m5.large OVERSIZED) $0.118, batch(c5.large IDLE) $0.096, web(t3.medium) $0.052 → 하루 $6.384
        // 월말 예상(10/7을 오늘로) = 6.384 × 6 + 6.384 × 25 = $197.904
        String setup = """
            {"from":"2026-09-20","to":"2026-10-07","trafficGrowthPercent":20,
             "budget":{"amount":100,"currency":"USD"},
             "recommendationIds":["i-0d3e0000000000002:IDLE","i-0d3e0000000000001:OVERSIZED"],
             "servers":[
              {"name":"api","instanceType":"m5.large","os":"LINUX","count":1,"schedule":"ALWAYS","usagePattern":"OVERSIZED"},
              {"name":"batch","instanceType":"c5.large","os":"LINUX","count":1,"schedule":"ALWAYS","usagePattern":"IDLE"},
              {"name":"web","instanceType":"t3.medium","os":"LINUX","count":1,"schedule":"ALWAYS"}]}
            """;

        JsonNode body = body(mvc.perform(post("/api/v1/demo/simulations?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(setup)).andExpect(status().isOk()));

        assertThat(body.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(body.at("/selectedCount").asInt()).isEqualTo(2);
        assertThat(body.at("/before/monthlyCost").decimalValue()).isEqualByComparingTo("237.48");
        assertThat(body.at("/after/monthlyCost").decimalValue()).isEqualByComparingTo("95.57");
        assertThat(body.at("/withinBudget").asBoolean()).isTrue();
        assertThat(body.at("/notes/0").asText()).contains("체험용 계정");

        mvc.perform(post("/api/v1/demo/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content(setup.replace("\"trafficGrowthPercent\":20", "\"trafficGrowthPercent\":100.9")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/demo/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content(setup.replace("i-0d3e0000000000002:IDLE", "nope")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_RECOMMENDATION"));
    }
}
