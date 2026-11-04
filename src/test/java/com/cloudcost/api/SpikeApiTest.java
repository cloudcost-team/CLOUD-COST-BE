package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 급증일 탐지 (#29). NOW = 2026-10-08 12:00 KST.
 * i-web(t3.medium $0.0416)은 8/1부터 계속 켜져 있어 하루 $0.9984, i-batch는 10/5(KST) 하루만 24시간 켜져 그날 비용이 2배다.
 */
class SpikeApiTest extends IntegrationTestBase {

    private String setUpAccount() throws Exception {
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-web", "t3.medium", "running", Instant.parse("2026-08-01T00:00:00Z")));
        aws.instances.add(Fixtures.instance("i-batch", "t3.medium", "stopped", Instant.parse("2026-10-04T15:00:00Z")));
        aws.addHours("i-web", Instant.parse("2026-07-31T15:00:00Z"), 69 * 24 + 12, 60);
        aws.addHours("i-batch", Instant.parse("2026-10-04T15:00:00Z"), 24, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
        return accountId;
    }

    @Test
    void 일별_비용에_급증일과_원인_서버가_붙는다() throws Exception {
        String accountId = setUpAccount();

        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily?currency=USD")
                .andExpect(status().isOk()));

        JsonNode days = body.at("/days");
        assertThat(days.size()).isEqualTo(30);
        int spikes = 0;
        for (JsonNode day : days) {
            if (!day.at("/spike").isNull()) {
                spikes++;
                assertThat(day.at("/date").asText()).isEqualTo("2026-10-05");
            }
        }
        assertThat(spikes).as("10/5 하루만 급증").isEqualTo(1);
        JsonNode spike = days.get(26).at("/spike");
        assertThat(days.get(26).at("/date").asText()).isEqualTo("2026-10-05");
        assertThat(spike.at("/ratio").decimalValue()).isEqualByComparingTo("2.0");
        assertThat(spike.at("/baseline").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(spike.at("/baselineDays").asInt()).isEqualTo(30);
        assertThat(spike.at("/causeInstanceId").asText()).isEqualTo("i-batch");
        assertThat(spike.at("/causeName").asText()).isEqualTo("name-i-batch");
        assertThat(spike.at("/causeUsageHours").decimalValue()).isEqualByComparingTo("24");
        assertThat(spike.at("/causeBaselineHours").decimalValue()).isEqualByComparingTo("0");
        assertThat(spike.at("/message").asText()).isEqualTo("10/5 비용 급증 · 평소의 2.0배 · name-i-batch 사용 시간이 늘었어요");
        assertThat(days.get(25).has("spike")).as("급증이 아닌 날도 spike 필드는 있다 (null)").isTrue();
        assertThat(body.at("/notes").toString()).contains("급증 = ");
    }

    @Test
    void 대시보드에_최근_30일_중_가장_최근_급증이_붙는다() throws Exception {
        String accountId = setUpAccount();

        JsonNode latest = body(getJson("/api/v1/aws/accounts/" + accountId + "/dashboard?currency=USD")
                .andExpect(status().isOk())).at("/latestSpike");

        assertThat(latest.at("/date").asText()).isEqualTo("2026-10-05");
        assertThat(latest.at("/cost").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(latest.at("/ratio").decimalValue()).isEqualByComparingTo("2.0");
        assertThat(latest.at("/causeInstanceId").asText()).isEqualTo("i-batch");
        assertThat(latest.at("/message").asText()).startsWith("10/5 비용 급증 · 평소의 2.0배");
    }

    @Test
    void 급증이_없으면_latestSpike는_null이다() throws Exception {
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-web", "t3.medium", "running", Instant.parse("2026-08-01T00:00:00Z")));
        aws.addHours("i-web", Instant.parse("2026-07-31T15:00:00Z"), 69 * 24 + 12, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));

        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/dashboard").andExpect(status().isOk()));

        assertThat(body.has("latestSpike")).isTrue();
        assertThat(body.at("/latestSpike").isNull()).isTrue();
    }

    /** 9/1~10/7: web은 24시간, batch는 매일 4시간 · 매달 12일만 24시간 */
    private static final String DEMO_SETUP = """
        {"from":"2026-09-01","to":"2026-10-07","servers":[
          {"name":"web","instanceType":"t3.medium","os":"LINUX","count":1,"schedule":"ALWAYS"},
          {"name":"batch","instanceType":"c5.large","os":"LINUX","count":1,"schedule":"MONTHLY_SPIKE","hoursPerDay":4}]}
        """;

    private JsonNode demo(String path) throws Exception {
        return body(mvc.perform(post("/api/v1/demo" + path).contentType(MediaType.APPLICATION_JSON).content(DEMO_SETUP))
                .andExpect(status().isOk()));
    }

    @Test
    void 체험_모드는_매달_12일_급증을_재현한다() throws Exception {
        JsonNode days = demo("/costs/daily?currency=USD").at("/days");

        // 평소 $0.052×24 + $0.096×4 = $1.632, 9/12는 $0.052×24 + $0.096×24 = $3.552 → 2.2배
        JsonNode sep12 = days.get(11);
        assertThat(sep12.at("/date").asText()).isEqualTo("2026-09-12");
        assertThat(sep12.at("/spike/ratio").decimalValue()).isEqualByComparingTo("2.2");
        assertThat(sep12.at("/spike/baseline").decimalValue()).isEqualByComparingTo("1.63");
        assertThat(sep12.at("/spike/baselineDays").asInt()).isEqualTo(11);
        assertThat(sep12.at("/spike/causeName").asText()).isEqualTo("batch");
        int spikes = 0;
        for (JsonNode day : days) {
            spikes += day.at("/spike").isNull() ? 0 : 1;
        }
        assertThat(spikes).isEqualTo(1);

        JsonNode latest = demo("/dashboard?currency=USD").at("/latestSpike");
        assertThat(latest.at("/date").asText()).isEqualTo("2026-09-12");
        assertThat(latest.at("/message").asText()).isEqualTo("9/12 비용 급증 · 평소의 2.2배 · batch 사용 시간이 늘었어요");
    }
}
