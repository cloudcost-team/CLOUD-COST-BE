package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 주간 요약 (#35 리뷰). NOW = 2026-10-08(목) KST, 지난주 = 9/28~10/4, 전주 = 9/21~9/27.
 * 전주에는 단가를 아는 i-old만, 지난주에는 단가를 모르는 i-x만 켜져 있었다.
 */
class WeeklySummaryPricingTest extends IntegrationTestBase {

    @Test
    void 지난주에_단가를_아는_사용이_없으면_0원으로_요약하지_않는다() throws Exception {
        activeAccount();
        aws.instances.add(Fixtures.instance("i-old", "t3.medium", "stopped", Instant.parse("2026-09-20T15:00:00Z")));
        aws.instances.add(Fixtures.instance("i-x", "x9.unknown", "stopped", Instant.parse("2026-09-27T15:00:00Z")));
        aws.addHours("i-old", Instant.parse("2026-09-20T15:00:00Z"), 7 * 24, 60);
        aws.addHours("i-x", Instant.parse("2026-09-27T15:00:00Z"), 7 * 24, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));

        JsonNode items = body(getJson("/api/v1/notifications").andExpect(status().isOk())).at("/items");

        assertThat(items.findValuesAsText("typeLabel")).doesNotContain("주간 요약");
    }
}
