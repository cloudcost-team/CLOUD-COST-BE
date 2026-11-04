package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 대시보드 요약·월 예산 (#27). NOW = 2026-10-08T03:00Z = 10/8(목) 12:00 KST.
 * i-run(t3.medium $0.0416)은 2026-09-01T00:00Z(9/1 09:00 KST)부터 계속 켜져 있다 → 하루 $0.9984.
 */
class DashboardApiTest extends IntegrationTestBase {

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.instances.add(Fixtures.instance("i-stop", "t3.medium", "stopped", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-09-01T00:00:00Z"), 37 * 24 + 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode dashboard(String currency) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/dashboard?currency=" + currency)
                .andExpect(status().isOk()));
    }

    private ResultActions putBudget(String json) throws Exception {
        return call(put("/api/v1/aws/accounts/" + accountId + "/budget")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void 이번_달_비용_대수_월말_예상_전월_같은_기간_대비를_준다() throws Exception {
        JsonNode body = dashboard("USD");

        assertThat(body.at("/today").asText()).isEqualTo("2026-10-08");
        assertThat(body.at("/month").asText()).isEqualTo("2026-10");
        assertThat(body.at("/monthToDate/usageHours").decimalValue()).as("7일 × 24 + 오늘 12시간").isEqualByComparingTo("180");
        assertThat(body.at("/monthToDate/estimatedCost").decimalValue()).isEqualByComparingTo("7.49");
        assertThat(body.at("/instanceCounts/running").asInt()).isEqualTo(1);
        assertThat(body.at("/instanceCounts/stopped").asInt()).isEqualTo(1);

        assertThat(body.at("/forecast/basisDays").asInt()).isEqualTo(7);
        assertThat(body.at("/forecast/remainingDays").asInt()).as("10/8~10/31").isEqualTo(24);
        assertThat(body.at("/forecast/monthEndCost").decimalValue()).as("6.9888 + 0.9984 × 24").isEqualByComparingTo("30.95");

        JsonNode prev = body.at("/previousMonthSamePeriod");
        assertThat(prev.at("/currentFrom").asText()).isEqualTo("2026-10-01");
        assertThat(prev.at("/currentTo").asText()).isEqualTo("2026-10-07");
        assertThat(prev.at("/previousTo").asText()).isEqualTo("2026-09-07");
        assertThat(prev.at("/currentCost").decimalValue()).isEqualByComparingTo("6.99");
        assertThat(prev.at("/previousCost").decimalValue()).as("9/1은 09시부터 15시간").isEqualByComparingTo("6.61");
        assertThat(prev.at("/change").decimalValue()).isEqualByComparingTo("0.38");
        assertThat(prev.at("/changeRate").decimalValue()).isEqualByComparingTo("5.7");

        assertThat(body.at("/budget").isNull()).as("예산을 정하지 않았으면 null").isTrue();
        assertThat(body.at("/notes").toString()).contains("월말 예상 =");
    }

    @Test
    void 예산을_정하면_월말_예상_대비_비율과_넘은_기준을_준다() throws Exception {
        putBudget("{\"amount\":40,\"currency\":\"USD\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(40.00)).andExpect(jsonPath("$.currency").value("USD"));

        JsonNode under = dashboard("USD").at("/budget");
        assertThat(under.at("/amount").decimalValue()).isEqualByComparingTo("40");
        assertThat(under.at("/usageRate").decimalValue()).isEqualByComparingTo("77.4");
        assertThat(under.at("/thresholdReached").isNull()).isTrue();

        putBudget("{\"amount\":30,\"currency\":\"USD\"}").andExpect(status().isOk());
        JsonNode over = dashboard("USD").at("/budget");
        assertThat(over.at("/usageRate").decimalValue()).isEqualByComparingTo("103.2");
        assertThat(over.at("/thresholdReached").asInt()).isEqualTo(100);
    }

    @Test
    void 원화_예산은_입력값_그대로_보이고_달러로_보면_환산한다() throws Exception {
        putBudget("{\"amount\":52000,\"currency\":\"KRW\"}").andExpect(status().isOk());

        JsonNode krw = dashboard("KRW").at("/budget");
        assertThat(krw.at("/amount").decimalValue()).isEqualByComparingTo("52000");
        assertThat(krw.at("/enteredCurrency").asText()).isEqualTo("KRW");
        assertThat(krw.at("/usageRate").decimalValue()).as("30.9504 ÷ (52000 ÷ 1300)").isEqualByComparingTo("77.4");

        JsonNode usd = dashboard("USD").at("/budget");
        assertThat(usd.at("/amount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(usd.at("/enteredAmount").decimalValue()).isEqualByComparingTo("52000");
    }

    @Test
    void 잘못된_예산은_400이고_지우면_null이며_남의_연결은_404다() throws Exception {
        putBudget("{\"amount\":-1,\"currency\":\"KRW\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        putBudget("{\"currency\":\"KRW\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.amount").value("숫자로 입력해 주세요 (예: 400000)"));
        putBudget("{\"amount\":\"사십만\",\"currency\":\"KRW\"}").andExpect(status().isBadRequest());

        putBudget("{\"amount\":400000,\"currency\":\"KRW\"}").andExpect(status().isOk());
        mvc.perform(as("someone-else", put("/api/v1/aws/accounts/" + accountId + "/budget")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"currency\":\"KRW\"}")))
                .andExpect(status().isNotFound());
        mvc.perform(as("someone-else", delete("/api/v1/aws/accounts/" + accountId + "/budget")))
                .andExpect(status().isNotFound());

        call(delete("/api/v1/aws/accounts/" + accountId + "/budget")).andExpect(status().isNoContent());
        assertThat(dashboard("KRW").at("/budget").isNull()).isTrue();
    }

    @Test
    void 연결을_지우면_예산도_함께_지운다() throws Exception {
        putBudget("{\"amount\":400000,\"currency\":\"KRW\"}").andExpect(status().isOk());

        call(delete("/api/v1/aws/accounts/" + accountId)).andExpect(status().isOk());

        putBudget("{\"amount\":400000,\"currency\":\"KRW\"}").andExpect(status().isNotFound());
    }

    @Test
    void 낭비_리소스에도_월말_예상_대비_비율이_붙는다() throws Exception {
        JsonNode waste = body(getJson("/api/v1/aws/accounts/" + accountId + "/waste?currency=USD")
                .andExpect(status().isOk()));

        assertThat(waste.at("/forecastMonthEndCost").decimalValue()).isEqualByComparingTo("30.95");
        assertThat(waste.at("/savingsRateOfForecast").decimalValue()).as("추천이 없으면 0%").isEqualByComparingTo("0");
    }

    @Test
    void 단가를_아는_서버가_없으면_0원이_아니라_계산하지_않았다고_알린다() throws Exception {
        priceCatalog.hourlyByType.clear();
        putBudget("{\"amount\":40,\"currency\":\"USD\"}").andExpect(status().isOk());

        JsonNode body = dashboard("USD");

        assertThat(body.at("/monthToDate/usageHours").decimalValue()).as("사용 시간은 그대로").isEqualByComparingTo("180");
        assertThat(body.at("/monthToDate/estimatedCost").isNull()).isTrue();
        assertThat(body.at("/forecast").isNull()).isTrue();
        assertThat(body.at("/previousMonthSamePeriod").isNull()).isTrue();
        assertThat(body.at("/budget/usageRate").isNull()).as("0%로 보이지 않게").isTrue();
        assertThat(body.at("/notes").toString()).contains("단가를 아는 서버가 없어");
    }

    @Test
    void 예산_화면은_지난_3개월_평균과_슬라이더_범위를_준다() throws Exception {
        // 7월은 기록 시작일(7/10) 전이 섞여 있고 8월은 서버를 켜기 전이라 평균에서 빠지고, 9월(711시간 × $0.0416)만 쓴다
        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/budget?currency=USD")
                .andExpect(status().isOk()));

        assertThat(body.at("/budget").isNull()).isTrue();
        assertThat(body.at("/forecastMonthEndCost").decimalValue()).isEqualByComparingTo("30.95");
        assertThat(body.at("/recentAverage/monthlyCost").decimalValue()).isEqualByComparingTo("29.58");
        assertThat(body.at("/recentAverage/months").toString()).isEqualTo("[\"2026-09\"]");
        assertThat(body.at("/slider/step").decimalValue()).isEqualByComparingTo("10");
        assertThat(body.at("/slider/max").decimalValue()).as("29.58 × 2 = 59.16 → 60").isEqualByComparingTo("60");
        assertThat(body.at("/slider/suggested").decimalValue()).isEqualByComparingTo("30");

        putBudget("{\"amount\":52000,\"currency\":\"KRW\"}").andExpect(status().isOk());
        JsonNode krw = body(getJson("/api/v1/aws/accounts/" + accountId + "/budget").andExpect(status().isOk()));
        assertThat(krw.at("/budget/amount").decimalValue()).isEqualByComparingTo("52000");
        assertThat(krw.at("/budget/usageRate").decimalValue()).isEqualByComparingTo("77.4");
        assertThat(krw.at("/slider/step").decimalValue()).isEqualByComparingTo("10000");
        assertThat(krw.at("/slider/max").decimalValue()).as("max(76,908, 60,354, 78,000) → 80,000").isEqualByComparingTo("80000");
        assertThat(krw.at("/slider/suggested").decimalValue()).as("지금 예산 52,000 → 60,000").isEqualByComparingTo("60000");

        mvc.perform(as("someone-else", org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/aws/accounts/" + accountId + "/budget")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 체험_모드도_예산_화면을_준다() throws Exception {
        String setup = """
            {"from":"2026-06-01","to":"2026-10-07","budget":{"amount":40,"currency":"USD"},"servers":[
              {"name":"web","instanceType":"t3.medium","os":"LINUX","count":1,"schedule":"ALWAYS"}]}
            """;
        JsonNode body = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/demo/budget?currency=USD").contentType(MediaType.APPLICATION_JSON).content(setup))
                .andExpect(status().isOk()));

        assertThat(body.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(body.at("/recentAverage/months").size()).as("7·8·9월").isEqualTo(3);
        assertThat(body.at("/recentAverage/monthlyCost").decimalValue())
                .as("$0.052 × 24 × (31 + 31 + 30) ÷ 3").isEqualByComparingTo("38.27");
        assertThat(body.at("/slider/suggested").decimalValue()).isEqualByComparingTo("40");
    }
}
