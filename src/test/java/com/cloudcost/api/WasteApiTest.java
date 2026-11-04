package com.cloudcost.api;

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
 * 낭비 리소스 API (#10). NOW = 2026-10-08T03:00Z, 판단 구간 = [2026-09-24T03:00Z, 2026-10-08T03:00Z) 336시간.
 * 세 서버 모두 14일 내내 켜져 있어 월 730시간으로 본다.
 */
class WasteApiTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T03:00:00Z");
    private static final long MB = 1024L * 1024L;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
        priceCatalog.hourlyByType.put("c5.large", new BigDecimal("0.096"));
        priceCatalog.hourlyByType.put("t3.small", new BigDecimal("0.026"));
    }

    private void threeServers() {
        server("i-api", "m5.large", 6, 23, 10 * MB);       // 과다 사양 → t3.medium
        server("i-batch", "c5.large", 0.4, 1.5, 100);      // 유휴
        server("i-web", "t3.medium", 50, 90, 50 * MB);     // 정상
    }

    private void server(String id, String type, double cpuAvg, double cpuMax, long networkPerHour) {
        aws.instances.add(Fixtures.instance(id, type, "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours(id, WINDOW_START, 336, cpuAvg, cpuMax);
        aws.addNetworkHours(id, WINDOW_START, 336, networkPerHour / 2, networkPerHour / 2);
    }

    private JsonNode waste(String currency) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/waste?currency=" + currency)
                .andExpect(status().isOk()));
    }

    @Test
    void 과다_사양과_유휴를_절감액_큰_순으로_근거와_함께_준다() throws Exception {
        threeServers();
        JsonNode body = waste("USD");

        assertThat(body.at("/dataSource").asText()).isEqualTo("aws");
        assertThat(body.at("/status").asText()).isEqualTo("OK");
        assertThat(body.at("/window/from").asText()).isEqualTo("2026-09-24T03:00:00Z");
        assertThat(body.at("/judgedCount").asInt()).isEqualTo(3);
        assertThat(body.at("/counts/oversized").asLong()).isEqualTo(1);
        assertThat(body.at("/counts/idle").asLong()).isEqualTo(1);
        assertThat(body.at("/totalMonthlySavings").decimalValue()).as("70.08 + 48.18").isEqualByComparingTo("118.26");

        JsonNode idle = body.at("/items/0");
        assertThat(idle.at("/recommendationId").asText()).isEqualTo("i-batch:IDLE");
        assertThat(idle.at("/kindLabel").asText()).isEqualTo("유휴");
        assertThat(idle.at("/action").asText()).isEqualTo("쓰지 않는다면 AWS 콘솔에서 중지 검토");
        assertThat(idle.at("/estimatedMonthlySavings").decimalValue()).isEqualByComparingTo("70.08");
        assertThat(idle.at("/comparison/recommended").isNull()).isTrue();

        JsonNode api = body.at("/items/1");
        assertThat(api.at("/recommendationId").asText()).isEqualTo("i-api:OVERSIZED");
        assertThat(api.at("/evidence").asText()).isEqualTo("최근 14일 CPU 평균 6% (P95 6%)");
        assertThat(api.at("/action").asText()).isEqualTo("m5.large → t3.medium 으로 변경 검토");
        assertThat(api.at("/metrics/cpuMax").decimalValue()).isEqualByComparingTo("23");
        assertThat(api.at("/metrics/cpuHours").asInt()).isEqualTo(336);
        assertThat(api.at("/monthlyHours").decimalValue()).isEqualByComparingTo("730");
        assertThat(api.at("/estimatedMonthlyCost").decimalValue()).isEqualByComparingTo("86.14");
        assertThat(api.at("/estimatedMonthlySavings").decimalValue()).isEqualByComparingTo("48.18");
        assertThat(api.at("/comparison/current/vcpu").asInt()).isEqualTo(2);
        assertThat(api.at("/comparison/current/memoryGib").decimalValue()).isEqualByComparingTo("8");
        assertThat(api.at("/comparison/current/hourlyCost").decimalValue()).isEqualByComparingTo("0.1180");
        assertThat(api.at("/comparison/recommended/instanceType").asText()).isEqualTo("t3.medium");
        assertThat(api.at("/comparison/recommended/memoryGib").decimalValue()).isEqualByComparingTo("4");
        assertThat(api.at("/comparison/recommended/monthlyCostAt730h").decimalValue()).isEqualByComparingTo("37.96");
        assertThat(api.at("/cautions").toString()).contains("메모리 사용률");
        assertThat(api.at("/consoleSteps").size()).isEqualTo(3);

        assertThat(body.at("/notes").toString()).contains("미연결 볼륨").contains("1시간 평균값 기준");
        assertThat(body.at("/skipped").size()).isZero();
    }

    @Test
    void 원화로_조회하면_환산한_금액을_준다() throws Exception {
        threeServers();
        JsonNode body = waste("KRW");

        assertThat(body.at("/currency").asText()).isEqualTo("KRW");
        assertThat(body.at("/exchangeRate").isNull()).isFalse();
        assertThat(body.at("/totalMonthlySavings").decimalValue()).isGreaterThan(new BigDecimal("1000"));
    }

    @Test
    void 줄일_만한_서버가_없으면_NONE_FOUND다() throws Exception {
        server("i-web", "t3.medium", 50, 90, 50 * MB);

        JsonNode body = waste("USD");

        assertThat(body.at("/status").asText()).isEqualTo("NONE_FOUND");
        assertThat(body.at("/items").size()).isZero();
        assertThat(body.at("/totalMonthlySavings").decimalValue()).isEqualByComparingTo("0");
    }
}
