package com.cloudcost.application;

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
 * 원하는 달 조회 (#21): 월별 응답의 기록 시작일(availableFrom)과 전월 대비 증감. 기준 시각은 2026-10-08 12:00 KST.
 */
class MonthlyViewTest extends IntegrationTestBase {

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-08-01T00:00:00Z")));
        // 8월 10시간, 9월 4시간, 10월 6시간 (KST 기준 각 달 안)
        aws.addHours("i-run", Instant.parse("2026-08-10T00:00:00Z"), 10, 60);
        aws.addHours("i-run", Instant.parse("2026-09-10T00:00:00Z"), 4, 60);
        aws.addHours("i-run", Instant.parse("2026-10-05T00:00:00Z"), 6, 60);
        // 단가 캐시는 테스트끼리 공유되므로 다른 테스트와 같은 단가를 쓴다
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode monthly(String from) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/monthly?currency=USD&from=" + from)
            .andExpect(status().isOk()));
    }

    @Test
    void 기록_시작일은_처음_본_인스턴스의_소급_시작일이다() throws Exception {
        JsonNode body = monthly("2026-06");

        // 처음 본 시각(2026-10-08 12:00 KST) − 소급 90일
        assertThat(body.at("/availableFrom").asText()).isEqualTo("2026-07-10");
        assertThat(body.at("/notes").toString()).contains("2026-07-10 이전은 사용량 기록이 없습니다");
        assertThat(body.at("/months")).hasSize(5);
    }

    @Test
    void 월마다_직전_달_대비_증감을_준다() throws Exception {
        JsonNode months = monthly("2026-08").at("/months");

        assertThat(months.at("/0/month").asText()).isEqualTo("2026-08");
        assertThat(months.at("/0/estimatedCost").decimalValue()).isEqualByComparingTo("0.42");
        assertThat(months.at("/0/estimatedCostChange").isNull()).as("첫 달은 비교 대상이 없다").isTrue();
        assertThat(months.at("/1/estimatedCostChange").decimalValue()).as("0.17 − 0.42").isEqualByComparingTo("-0.25");
        assertThat(months.at("/2/estimatedCostChange").decimalValue()).as("0.25 − 0.17").isEqualByComparingTo("0.08");
        assertThat(months.at("/2/instances/0/usageHours").decimalValue()).isEqualByComparingTo("6");
    }

    @Test
    void 기간_합계와_일별에도_기록_시작일이_있다() throws Exception {
        String base = "/api/v1/aws/accounts/" + accountId;
        assertThat(body(getJson(base + "/costs?currency=USD")).at("/availableFrom").asText()).isEqualTo("2026-07-10");
        assertThat(body(getJson(base + "/costs/daily?currency=USD")).at("/availableFrom").asText()).isEqualTo("2026-07-10");
    }
}
