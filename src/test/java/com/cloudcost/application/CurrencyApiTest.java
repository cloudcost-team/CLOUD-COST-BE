package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 원화(KRW) 기본 조회와 원 ↔ 달러 전환 API (ADR-005). 환율은 가짜 1,300원. */
class CurrencyApiTest extends IntegrationTestBase {

    private String accountId;

    @BeforeEach
    void connect() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-10-07T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 10, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    @Test
    void 기본은_원화로_환산하고_사용한_환율을_함께_준다() throws Exception {
        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily?from=2026-10-07&to=2026-10-07")
                .andExpect(status().isOk()));
        assertThat(body.at("/currency").asText()).isEqualTo("KRW");
        assertThat(body.at("/exchangeRate/rate").decimalValue()).isEqualByComparingTo("1300.00");
        assertThat(body.at("/exchangeRate/date").asText()).isEqualTo("2026-10-07");
        // 10시간 × $0.0416 = $0.416 → × 1,300 = 540.8 → 541원
        assertThat(body.at("/days/0/estimatedCost").decimalValue()).isEqualByComparingTo("541");
        assertThat(body.at("/days/0/instances/0/estimatedHourlyCost").decimalValue()).isEqualByComparingTo("54.1");
        assertThat(body.at("/usageHours").decimalValue()).as("사용 시간은 통화와 무관").isEqualByComparingTo("10.00");
        assertThat(body.at("/notes").toString()).contains("원화 금액은 2026-10-07 환율(1 USD = 1,300.00원");
    }

    @Test
    void currency_USD로_바꾸면_달러로_응답한다() throws Exception {
        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily?from=2026-10-07&to=2026-10-07&currency=USD")
                .andExpect(status().isOk()));
        assertThat(body.at("/currency").asText()).isEqualTo("USD");
        assertThat(body.at("/exchangeRate").isNull()).isTrue();
        assertThat(body.at("/days/0/estimatedCost").decimalValue()).isEqualByComparingTo("0.42");
    }

    @Test
    void 인스턴스_목록도_원화가_기본() throws Exception {
        getJson("/api/v1/aws/accounts/" + accountId + "/instances")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.instances[0].estimatedHourlyCost").value(54.1));
    }

    @Test
    void 지원하지_않는_통화는_400() throws Exception {
        getJson("/api/v1/aws/accounts/" + accountId + "/costs?currency=EUR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
