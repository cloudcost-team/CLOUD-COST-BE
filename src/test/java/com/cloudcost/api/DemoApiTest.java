package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 체험 모드: 간이 로그인 → 서버 구성·기간 → 실제 연결과 같은 형식의 비용. Google 로그인·AWS·DB 없이 동작한다.
 * 기준 시각은 2026-10-08(목) 12:00 KST.
 */
class DemoApiTest extends IntegrationTestBase {

    /** 10/1~10/7: web(m5.large 2대, 24시간) + dev(t3.medium Windows, 평일 09~19시) + gpu(g4dn.xlarge, 매일 6시간) */
    private static final String SETUP = """
        {"from":"2026-10-01","to":"2026-10-07","servers":[
          {"name":"web","instanceType":"m5.large","os":"LINUX","count":2,"schedule":"ALWAYS"},
          {"name":"dev","instanceType":"t3.medium","os":"WINDOWS","count":1,"schedule":"WEEKDAY_OFFICE"},
          {"name":"gpu","instanceType":"g4dn.xlarge","os":"LINUX","count":1,"schedule":"DAILY_HOURS","hoursPerDay":6}]}
        """;

    private ResultActions postDemo(String path, String body) throws Exception {
        return mvc.perform(post("/api/v1/demo" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode demo(String path) throws Exception {
        return body(postDemo(path, SETUP).andExpect(status().isOk()));
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void 간이_로그인은_정해진_아이디와_비밀번호만_받는다() throws Exception {
        JsonNode ok = body(postDemo("/login", "{\"username\":\"cloudcost\",\"password\":\"1234\"}")
            .andExpect(status().isOk()));
        assertThat(ok.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(ok.at("/awsAccountId").asText()).isEqualTo("123456789012");
        assertThat(ok.at("/name").asText()).isEqualTo("체험용 AWS 계정");

        JsonNode wrong = body(postDemo("/login", "{\"username\":\"cloudcost\",\"password\":\"0000\"}")
            .andExpect(status().isUnauthorized()));
        assertThat(wrong.at("/error/code").asText()).isEqualTo("DEMO_LOGIN_FAILED");
    }

    @Test
    void 체험_계정은_실제_연결_목록에_들어가지_않는다() throws Exception {
        postDemo("/login", "{\"username\":\"cloudcost\",\"password\":\"1234\"}").andExpect(status().isOk());
        demo("/costs");

        assertThat(body(getJson("/api/v1/aws/accounts").andExpect(status().isOk()))).isEmpty();
        assertThat(aws.assumeCalls).isZero();
        assertThat(aws.describeCalls).isZero();
    }

    @Test
    void 카탈로그로_고를_수_있는_타입과_패턴을_준다() throws Exception {
        JsonNode body = body(mvc.perform(get("/api/v1/demo/catalog")).andExpect(status().isOk()));

        assertThat(body.at("/instanceTypes").size()).isGreaterThan(5);
        assertThat(body.at("/schedules/0/code").asText()).isEqualTo("ALWAYS");
        assertThat(body.at("/usagePatterns").findValuesAsText("code")).containsExactly("NORMAL", "OVERSIZED", "IDLE");
        assertThat(body.at("/os").toString()).contains("WINDOWS");
    }

    @Test
    void 설정대로_인스턴스_목록을_준다() throws Exception {
        JsonNode body = demo("/instances?currency=USD");

        assertThat(body.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(body.at("/instances")).hasSize(4);
        assertThat(body.at("/instances").findValuesAsText("name")).containsExactlyInAnyOrder("web-1", "web-2", "dev", "gpu");
        assertThat(body.at("/notes/0").asText()).contains("체험용 계정");
    }

    @Test
    void 기간_합계는_사용_시간_곱하기_단가다() throws Exception {
        JsonNode body = demo("/costs?currency=USD");

        assertThat(body.at("/from").asText()).isEqualTo("2026-10-01");
        assertThat(body.at("/to").asText()).isEqualTo("2026-10-07");
        // 24h×7일 ×2대, 평일 5일(10/1~2, 10/5~7)×10h, 6h×7일
        assertThat(body.at("/usageHours").decimalValue()).isEqualByComparingTo(String.valueOf(168 * 2 + 50 + 42));
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode i : body.at("/instances")) {
            BigDecimal expected = i.at("/usageHours").decimalValue().multiply(i.at("/estimatedHourlyCost").decimalValue());
            assertThat(i.at("/estimatedCost").decimalValue())
                .isEqualByComparingTo(expected.setScale(2, RoundingMode.HALF_UP));
            sum = sum.add(i.at("/estimatedCost").decimalValue());
        }
        assertThat(body.at("/estimatedCost").decimalValue()).isCloseTo(sum, within(new BigDecimal("0.05")));
    }

    @Test
    void 일별_월별도_설정한_기간으로_준다() throws Exception {
        assertThat(demo("/costs/daily?currency=USD").at("/days")).hasSize(7);

        String twoMonths = SETUP.replace("\"from\":\"2026-10-01\"", "\"from\":\"2026-09-15\"");
        JsonNode monthly = body(postDemo("/costs/monthly?currency=USD", twoMonths).andExpect(status().isOk()));
        assertThat(monthly.at("/months")).hasSize(2);
        assertThat(monthly.at("/months/0/month").asText()).isEqualTo("2026-09");
        assertThat(monthly.at("/availableFrom").asText()).as("체험은 설정한 시작일부터 기록이 있다").isEqualTo("2026-09-15");
        assertThat(monthly.at("/months/1/estimatedCostChange").decimalValue()).isEqualByComparingTo(
            monthly.at("/months/1/estimatedCost").decimalValue().subtract(monthly.at("/months/0/estimatedCost").decimalValue()));
    }

    @Test
    void 원화로도_조회된다() throws Exception {
        JsonNode body = demo("/costs");

        assertThat(body.at("/currency").asText()).isEqualTo("KRW");
        assertThat(body.at("/exchangeRate/rate").isMissingNode()).isFalse();
    }

    @Test
    void 잘못된_설정은_400으로_이유를_알려_준다() throws Exception {
        String future = SETUP.replace("\"to\":\"2026-10-07\"", "\"to\":\"2026-10-09\"");
        JsonNode body = body(postDemo("/costs", future).andExpect(status().isBadRequest()));
        assertThat(body.at("/error/code").asText()).isEqualTo("DEMO_INVALID_SETUP");
        assertThat(body.at("/error/message").asText()).contains("오늘까지");

        String unknownType = SETUP.replace("m5.large", "x9.huge");
        assertThat(body(postDemo("/costs", unknownType).andExpect(status().isBadRequest())).at("/error/message").asText())
            .contains("x9.huge");
    }

    @Test
    void 응답_형식은_실제_계정_조회와_같다() throws Exception {
        String accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-10-07T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-10-07T00:00:00Z"), 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
        String real = "/api/v1/aws/accounts/" + accountId;

        for (String path : List.of("/instances", "/costs", "/costs/daily", "/costs/monthly")) {
            JsonNode aws = body(getJson(real + path + "?currency=USD").andExpect(status().isOk()));
            JsonNode demo = demo(path + "?currency=USD");
            assertThat(fields(demo)).as(path).isEqualTo(fields(aws));
        }
        JsonNode awsRow = body(getJson(real + "/costs?currency=USD")).at("/instances/0");
        assertThat(fields(demo("/costs?currency=USD").at("/instances/0"))).isEqualTo(fields(awsRow));
        JsonNode awsInstance = body(getJson(real + "/instances?currency=USD")).at("/instances/0");
        assertThat(fields(demo("/instances?currency=USD").at("/instances/0"))).isEqualTo(fields(awsInstance));
    }
}
