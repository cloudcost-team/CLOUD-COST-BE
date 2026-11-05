package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 월간 리포트 (#37). NOW = 2026-10-08 12:00 KST, 기록 시작일 = 7/10 (처음 본 날 − 90일).
 * 9/1 09:00 KST부터 계속 켜진 서버 2대: i-api(m5.large $0.118, CPU 6% → 과다 사양), i-run(t3.medium $0.0416, CPU 지표 없음).
 * 9월 = 711시간, 10월 = 180시간.
 */
class ReportApiTest extends IntegrationTestBase {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final int HOURS = 37 * 24 + 3;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-api", "m5.large", "running", START));
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", START));
        aws.addCpuHours("i-api", START, HOURS, 6, 23);
        aws.addNetworkHours("i-api", START, HOURS, 5L * 1024 * 1024, 5L * 1024 * 1024);
        aws.addHours("i-run", START, HOURS, 60);
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
    }

    private JsonNode get200(String path) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + path).andExpect(status().isOk()));
    }

    @Test
    void 목록은_기록_시작_달부터_이번_달까지_최신_달_먼저_상태와_전월_대비를_준다() throws Exception {
        JsonNode body = get200("/reports?currency=USD");

        assertThat(body.at("/availableFrom").asText()).isEqualTo("2026-07-10");
        assertThat(body.at("/months").findValuesAsText("month")).containsExactly("2026-10", "2026-09", "2026-08", "2026-07");
        assertThat(body.at("/months").findValuesAsText("status")).containsExactly("PREVIEW", "FINAL", "EMPTY", "EMPTY");
        JsonNode sep = body.at("/months/1");
        assertThat(sep.at("/estimatedCost").decimalValue()).as("711시간 × (0.118 + 0.052)").isEqualByComparingTo("120.87");
        assertThat(sep.at("/changeRate").isNull()).as("8월이 0원이면 증감률 없음").isTrue();
        JsonNode oct = body.at("/months/0");
        assertThat(oct.at("/statusLabel").asText()).isEqualTo("진행 중");
        assertThat(oct.at("/estimatedCost").decimalValue()).as("180시간 × 0.17").isEqualByComparingTo("30.60");
        assertThat(oct.at("/changeRate").decimalValue()).isEqualByComparingTo("-74.7");
    }

    @Test
    void 상세는_서버별_비중과_그_달_마지막_14일_절감_제안을_준다() throws Exception {
        JsonNode body = get200("/reports/2026-09?currency=USD");

        assertThat(body.at("/status").asText()).isEqualTo("FINAL");
        assertThat(body.at("/from").asText()).isEqualTo("2026-09-01");
        assertThat(body.at("/to").asText()).isEqualTo("2026-09-30");
        assertThat(body.at("/summary/estimatedCost").decimalValue()).isEqualByComparingTo("120.87");
        assertThat(body.at("/spikes/count").asInt()).as("9/1부터 일정하게 썼으므로 급증 없음").isZero();

        JsonNode top = body.at("/topServers");
        assertThat(top.findValuesAsText("instanceId")).containsExactly("i-api", "i-run");
        assertThat(top.at("/0/share").decimalValue()).as("0.118 ÷ 0.17").isEqualByComparingTo("69.4");
        assertThat(top.at("/0/name").asText()).isEqualTo("name-i-api");
        assertThat(top.at("/0/others").asBoolean()).isFalse();

        JsonNode savings = body.at("/savings");
        assertThat(savings.at("/status").asText()).isEqualTo("OK");
        assertThat(savings.at("/window/to").asText()).as("10/1 0시(KST)").isEqualTo("2026-09-30T15:00:00Z");
        assertThat(savings.at("/count").asInt()).isEqualTo(1);
        assertThat(savings.at("/items/0/recommendationId").asText()).isEqualTo("i-api:OVERSIZED");
        assertThat(savings.at("/monthlySavings").decimalValue()).as("(0.118 − 0.052) × 730").isEqualByComparingTo("48.18");
        assertThat(body.at("/notes").toString()).contains("그 달 마지막 14일");
    }

    @Test
    void 기록이_없는_달은_EMPTY이고_아직_오지_않은_달은_400_남의_연결은_404다() throws Exception {
        JsonNode empty = get200("/reports/2026-07");
        assertThat(empty.at("/status").asText()).isEqualTo("EMPTY");
        assertThat(empty.at("/savings").isNull()).isTrue();
        assertThat(empty.at("/topServers").size()).isZero();
        assertThat(empty.at("/spikes/count").asInt()).isZero();

        JsonNode current = get200("/reports/2026-10");
        assertThat(current.at("/status").asText()).isEqualTo("PREVIEW");
        assertThat(current.at("/to").asText()).isEqualTo("2026-10-08");

        getJson("/api/v1/aws/accounts/" + accountId + "/reports/2026-11").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PERIOD"));
        getJson("/api/v1/aws/accounts/" + accountId + "/reports/2026-13").andExpect(status().isBadRequest());
        mvc.perform(as("someone-else", get("/api/v1/aws/accounts/" + accountId + "/reports/2026-09")))
                .andExpect(status().isNotFound());
    }

    /** 8/15~10/7: web 24시간, batch 매일 4시간 · 매달 12일 24시간 */
    private static final String DEMO = """
        {"from":"2026-08-15","to":"2026-10-07","servers":[
          {"name":"web","instanceType":"t3.medium","os":"LINUX","count":1,"schedule":"ALWAYS"},
          {"name":"batch","instanceType":"c5.large","os":"LINUX","count":1,"schedule":"MONTHLY_SPIKE","hoursPerDay":4,
           "usagePattern":"OVERSIZED"}]}
        """;

    private JsonNode demo(String path) throws Exception {
        return body(mvc.perform(post("/api/v1/demo" + path).contentType(MediaType.APPLICATION_JSON).content(DEMO))
                .andExpect(status().isOk()));
    }

    @Test
    void 체험_모드도_같은_계산으로_리포트를_준다() throws Exception {
        JsonNode list = demo("/reports?currency=USD");
        assertThat(list.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(list.at("/months").findValuesAsText("status")).containsExactly("PREVIEW", "FINAL", "FIRST_MONTH");

        JsonNode aug = demo("/reports/2026-08?currency=USD");
        assertThat(aug.at("/status").asText()).isEqualTo("FIRST_MONTH");
        assertThat(aug.at("/from").asText()).isEqualTo("2026-08-15");
        assertThat(aug.at("/summary/previousMonthCost").isNull()).isTrue();

        JsonNode sep = demo("/reports/2026-09?currency=USD");
        assertThat(sep.at("/spikes/count").asInt()).isEqualTo(1);
        assertThat(sep.at("/spikes/days/0/date").asText()).isEqualTo("2026-09-12");
        assertThat(sep.at("/spikes/days/0/causeName").asText()).isEqualTo("batch");
        assertThat(sep.at("/topServers").size()).isEqualTo(2);
        assertThat(sep.at("/savings/status").asText()).isIn("OK", "NONE_FOUND");

        assertThat(demo("/reports/2026-07").at("/status").asText()).isEqualTo("EMPTY");
        mvc.perform(post("/api/v1/demo/reports/2026-11").contentType(MediaType.APPLICATION_JSON).content(DEMO))
                .andExpect(status().isBadRequest());
    }
}
