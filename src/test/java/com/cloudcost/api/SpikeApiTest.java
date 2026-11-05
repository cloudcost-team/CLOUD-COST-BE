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

    @Test
    void 체험_대시보드도_기간_앞_30일로_평소를_잰다() throws Exception {
        // 8/1부터 기록이 있으므로 대시보드 기간(9/1~) 앞의 8월도 평소에 들어간다 → 9/12의 평소를 잰 날은 30일
        String setup = DEMO_SETUP.replace("2026-09-01", "2026-08-01");
        JsonNode latest = body(mvc.perform(post("/api/v1/demo/dashboard?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(setup)).andExpect(status().isOk())).at("/latestSpike");

        assertThat(latest.at("/date").asText()).isEqualTo("2026-09-12");
        assertThat(latest.at("/baselineDays").asInt()).isEqualTo(30);
        assertThat(latest.at("/ratio").decimalValue()).isEqualByComparingTo("2.2");
    }

    @Test
    void 처음_쓰기_전_0원인_날은_평소에_넣지_않고_조회_기간이_달라도_판정이_같다() throws Exception {
        // 기록 시작일(7/10) 뒤 9/1에 처음 켠 서버를 그 뒤로 일정하게 썼다 → 어느 기간으로 조회해도 급증이 없다
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-new", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addHours("i-new", Instant.parse("2026-08-31T15:00:00Z"), 37 * 24 + 12, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));

        for (String query : new String[] {"", "?from=2026-09-10&to=2026-10-08", "?from=2026-09-20&to=2026-10-08"}) {
            JsonNode days = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily" + query)
                    .andExpect(status().isOk())).at("/days");
            for (JsonNode day : days) {
                assertThat(day.at("/spike").isNull()).as(query + " " + day.at("/date").asText()).isTrue();
            }
        }
        assertThat(body(getJson("/api/v1/aws/accounts/" + accountId + "/dashboard")).at("/latestSpike").isNull()).isTrue();
    }

    @Test
    void 처음_사용한_날이_평소_구간_밖이어도_그_뒤_꺼_둔_날은_평소에_넣는다() throws Exception {
        // 8/20에 처음 켜 9/6까지 쓰고, 9/7~9/16 꺼 뒀다가 9/17부터 다시 썼다 (하루 2시간·4시간 번갈아). 10/7은 4시간.
        // 10/7의 평소 구간(9/7~10/6) = 꺼 둔 0원 10일 + 2시간 10일 + 4시간 10일 → 중앙값 2시간 → 2.0배 급증.
        // 조회 범위 안에서만 처음 사용한 날을 찾으면 짧은 조회는 9/17을 처음으로 보고 0원을 빼 중앙값 3시간 → 급증이 아니게 된다.
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-app", "t3.medium", "running", Instant.parse("2026-08-19T15:00:00Z")));
        Instant aug20 = Instant.parse("2026-08-19T15:00:00Z");
        for (int day = 0; day < 49; day++) {
            boolean off = day >= 18 && day < 28; // 9/7~9/16
            if (!off) {
                aws.addHours("i-app", aug20.plusSeconds(86400L * day), day == 48 ? 4 : (day % 2 == 0 ? 2 : 4), 60);
            }
        }
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));

        JsonNode longQuery = spikesByDate(accountId, "?from=2026-09-01&to=2026-10-08");
        JsonNode shortQuery = spikesByDate(accountId, "?from=2026-10-07&to=2026-10-08");

        assertThat(longQuery.at("/2026-10-07/ratio").decimalValue()).isEqualByComparingTo("2.0");
        assertThat(longQuery.at("/2026-10-07/baselineDays").asInt()).as("꺼 둔 날도 평소에 들어간다").isEqualTo(30);
        assertThat(shortQuery.get("2026-10-07")).as("짧은 조회도 같은 판정").isEqualTo(longQuery.get("2026-10-07"));
        assertThat(shortQuery.get("2026-10-08")).isEqualTo(longQuery.get("2026-10-08"));
    }

    /** 일별 응답의 spike를 날짜별로 모은다 */
    private JsonNode spikesByDate(String accountId, String query) throws Exception {
        JsonNode days = body(getJson("/api/v1/aws/accounts/" + accountId + "/costs/daily?currency=USD&" + query.substring(1))
                .andExpect(status().isOk())).at("/days");
        com.fasterxml.jackson.databind.node.ObjectNode byDate = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (JsonNode day : days) {
            byDate.set(day.at("/date").asText(), day.at("/spike"));
        }
        return byDate;
    }
}
