package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * 예산 화면 최근 3개월 평균 (#41 리뷰). NOW = 2026-10-08 KST, 소급 455일.
 * i-old(t3.medium $0.05)는 2026년 1~6월 계속 켜져 있다가 7·8월 꺼 두고 9월에 다시 한 달 내내 켰다.
 */
@TestPropertySource(properties = "cci.usage.backfill=455d")
class BudgetLongHistoryTest extends IntegrationTestBase {

    @Test
    void 기간_앞에서_이미_쓰던_연결은_기간_안의_0원인_달도_평균에_넣는다() throws Exception {
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-old", "t3.medium", "stopped", Instant.parse("2026-08-31T15:00:00Z")));
        aws.addHours("i-old", Instant.parse("2025-12-31T15:00:00Z"), 181 * 24, 60);
        aws.addHours("i-old", Instant.parse("2026-08-31T15:00:00Z"), 30 * 24, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.05"));

        JsonNode average = body(getJson("/api/v1/aws/accounts/" + accountId + "/budget?currency=USD")
                .andExpect(status().isOk())).at("/recentAverage");

        assertThat(average.at("/months").toString()).isEqualTo("[\"2026-07\",\"2026-08\",\"2026-09\"]");
        assertThat(average.at("/monthlyCost").decimalValue()).as("(0 + 0 + 720시간 × 0.05) ÷ 3")
                .isEqualByComparingTo("12.00");
    }
}
