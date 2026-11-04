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
 * 월간 리포트 목록 (#37 리뷰): 기록이 14개월보다 길면 가장 오래된 달(2025-09)도 전월(2025-08)과 비교한다.
 * NOW = 2026-10-08 12:00 KST, 소급 455일 → 기록 시작일 2025-07-10. i-old(t3.medium $0.05)는 2025-08-01 09:00 KST부터 계속 켜져 있다.
 */
@TestPropertySource(properties = "cci.usage.backfill=455d")
class ReportLongHistoryTest extends IntegrationTestBase {

    @Test
    void 가장_오래된_달도_목록_밖의_전월과_비교한다() throws Exception {
        String accountId = activeAccount();
        Instant start = Instant.parse("2025-08-01T00:00:00Z");
        aws.instances.add(Fixtures.instance("i-old", "t3.medium", "running", start));
        aws.addHours("i-old", start, 433 * 24 + 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.05"));

        JsonNode months = body(getJson("/api/v1/aws/accounts/" + accountId + "/reports?currency=USD")
                .andExpect(status().isOk())).at("/months");

        assertThat(months.size()).isEqualTo(14);
        JsonNode oldest = months.get(13);
        assertThat(oldest.at("/month").asText()).isEqualTo("2025-09");
        assertThat(oldest.at("/status").asText()).isEqualTo("FINAL");
        assertThat(oldest.at("/estimatedCost").decimalValue()).as("720시간 × 0.05").isEqualByComparingTo("36.00");
        assertThat(oldest.at("/changeRate").decimalValue()).as("8월 735시간 × 0.05 = 36.75 대비").isEqualByComparingTo("-2.0");
    }
}
