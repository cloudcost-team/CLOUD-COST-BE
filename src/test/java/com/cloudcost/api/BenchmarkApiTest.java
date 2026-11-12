package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceType;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 팀 벤치마킹 (#33). NOW = 2026-10-08 12:00 KST. 서버 구성은 SimulationApiTest와 같다 → 월말 예상 $197.904.
 * i-api(P95 6%)·i-batch(P95 0.4%)는 과다 사양, i-web(P95 50%)은 적정 → 3대 중 2대 = 66.7%.
 */
class BenchmarkApiTest extends IntegrationTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-09-24T03:00:00Z");

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        priceCatalog.hourlyByType.put("m5.large", new BigDecimal("0.118"));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.052"));
        priceCatalog.hourlyByType.put("c5.large", new BigDecimal("0.096"));
    }

    private void server(String id, String type, double cpuAvg, double cpuMax, long networkPerHour) {
        aws.instances.add(Fixtures.instance(id, type, "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours(id, WINDOW_START, 336, cpuAvg, cpuMax);
        aws.addNetworkHours(id, WINDOW_START, 336, networkPerHour / 2, networkPerHour / 2);
    }

    private void threeServers() {
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-batch", "c5.large", 0.4, 1.5, 100);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);
    }

    private JsonNode benchmark(String query) throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/benchmark?currency=USD" + query)
                .andExpect(status().isOk()));
    }

    @Test
    void 과다_사양_비율과_서버별_판정_낭비_비율_불가_월_지출_위치를_준다() throws Exception {
        threeServers();
        JsonNode body = benchmark("");

        assertThat(body.at("/status").asText()).isEqualTo("OK");
        JsonNode oversized = body.at("/oversizedRatio");
        assertThat(oversized.at("/status").asText()).isEqualTo("OK");
        assertThat(oversized.at("/ours/judgedCount").asInt()).isEqualTo(3);
        assertThat(oversized.at("/ours/oversizedCount").asInt()).isEqualTo(2);
        assertThat(oversized.at("/ours/ratio").decimalValue()).isEqualByComparingTo("66.7");
        assertThat(oversized.at("/reference/source").asText()).isEqualTo("Azure Public Dataset V2");
        assertThat(oversized.at("/reference/sizeBand").asText()).isEqualTo("3-20");
        assertThat(oversized.at("/reference/sampleSize").asInt()).isEqualTo(2622);
        assertThat(oversized.at("/reference/averageRatio").decimalValue()).isEqualByComparingTo("42");
        assertThat(oversized.at("/reference/medianRatio").decimalValue()).isEqualByComparingTo("33");
        assertThat(oversized.at("/reference/percentileAvailable").asBoolean()).isTrue();
        assertThat(oversized.at("/moreEfficientTeamsPercent").decimalValue())
                .as("3~20대 표에서 66.7%보다 확실히 낮은 가장 큰 백분위는 65% (57.9%)").isEqualByComparingTo("65.0");
        assertThat(oversized.at("/servers").findValuesAsText("instanceId")).containsExactly("i-batch", "i-api", "i-web");
        assertThat(oversized.at("/servers/2/verdict").asText()).isEqualTo("적정");
        assertThat(oversized.at("/afterSimulation").isNull()).isTrue();

        JsonNode wasteRatio = body.at("/wasteRatio");
        assertThat(wasteRatio.at("/status").asText()).isEqualTo("OK");
        assertThat(wasteRatio.at("/reason").isNull()).isTrue();
        assertThat(wasteRatio.at("/ratioPercent").decimalValue())
                .as("방치 리소스 없음: 서버 낭비 118.26 ÷ 월말 예상 197.904").isEqualByComparingTo("59.8");
        assertThat(wasteRatio.at("/comparison").asText()).isEqualTo("ABOVE");
        assertThat(wasteRatio.at("/amounts/resourceWaste").decimalValue()).isEqualByComparingTo("0");
        assertThat(wasteRatio.at("/afterSimulationRatioPercent").isNull()).isTrue();
        assertThat(wasteRatio.at("/referenceAverage").decimalValue()).isEqualByComparingTo("29");

        JsonNode spend = body.at("/monthlySpend");
        assertThat(spend.at("/status").asText()).isEqualTo("OK");
        assertThat(spend.at("/monthlyCost").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(spend.at("/monthlyCostUsd").decimalValue()).isEqualByComparingTo("197.90");
        assertThat(spend.at("/servers").asInt()).isEqualTo(3);
        assertThat(spend.at("/monthlyVcpuHours").decimalValue())
                .as("2vCPU 3대 × 336시간 × 30일 ÷ 14일").isEqualByComparingTo("4320.0");
        assertThat(spend.at("/teamsUsingLessPercent").decimalValue())
                .as("모델이 예측한 서버 3대 표에서 4320보다 확실히 적은 가장 큰 백분위는 35% (4203.6). 40%는 4320으로 같아 넣지 않는다")
                .isEqualByComparingTo("35.0");
        assertThat(spend.at("/range/source").asText()).isEqualTo("Azure Public Dataset V2");
        assertThat(spend.at("/range/servers").asInt()).isEqualTo(3);
        assertThat(spend.at("/range/label").asText()).isEqualTo("서버 3대");
        assertThat(spend.at("/range/minVcpuHours").decimalValue()).isEqualByComparingTo("2365.3");
        assertThat(spend.at("/range/medianVcpuHours").decimalValue()).as("2vCPU 3대를 한 달 내내").isEqualByComparingTo("4320.0");
        assertThat(spend.at("/range/maxVcpuHours").decimalValue()).isEqualByComparingTo("5573.6");
        assertThat(spend.at("/range/minUsd").decimalValue())
                .as("2365.3 × (197.904 ÷ 4320) — 우리 vCPU-시간당 비용으로 환산").isEqualByComparingTo("108.36");
        assertThat(spend.at("/range/maxUsd").decimalValue()).isEqualByComparingTo("255.33");
        assertThat(spend.at("/position").asText()).isEqualTo("WITHIN");
        assertThat(spend.at("/barPositionPercent").decimalValue()).isEqualByComparingTo("60.9");
        JsonNode model = spend.at("/range/model");
        assertThat(model.at("/method").asText()).isEqualTo("QUANTILE_REGRESSION");
        assertThat(model.at("/sampleSize").asInt()).isEqualTo(5452);
        assertThat(model.at("/maxServers").asInt()).isEqualTo(50);
        assertThat(model.at("/testSize").asInt()).isEqualTo(1118);
        assertThat(model.at("/improvementPercent").decimalValue()).as("구간별 백분위 표보다 시험용 오차 감소").isEqualByComparingTo("15.3");
        assertThat(model.at("/middleHalfCoveragePercent").decimalValue()).isEqualByComparingTo("51.9");
        assertThat(body.at("/selectedMonthlySavings").isNull()).as("추천을 고르지 않으면 null").isTrue();

        JsonNode distribution = oversized.at("/distribution");
        assertThat(distribution.at("/approximate").asBoolean()).isTrue();
        assertThat(distribution.at("/sizeBand").asText()).isEqualTo("3-20");
        assertThat(distribution.at("/buckets")).hasSize(5);
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (JsonNode b : distribution.at("/buckets")) {
            total = total.add(b.at("/teamsPercent").decimalValue());
        }
        assertThat(total).isEqualByComparingTo("100");
        assertThat(distribution.at("/oursBucketIndex").asInt()).as("66.7%는 60~80% 칸").isEqualTo(3);
        assertThat(distribution.at("/buckets/3/ours").asBoolean()).isTrue();
        assertThat(distribution.at("/buckets/3/label").asText()).isEqualTo("60~80%");
        assertThat(distribution.at("/afterSimulationBucketIndex").isNull()).isTrue();
        assertThat(spend.at("/afterSimulation").isNull()).isTrue();
        assertThat(body.at("/notes").toString()).as("백분위 표가 있어 안내 없음").doesNotContain("백분위 표가 없어");
    }

    @Test
    void 월_지출에서_뺀_단가_모르는_서버를_안내에_보여_준다() throws Exception {
        threeServers();
        server("i-x", "x9.unknown", 50, 90, 50L * 1024 * 1024);

        JsonNode body = benchmark("");

        assertThat(body.at("/notes").toString()).as("대시보드 월말 예상에서 뺀 서버 안내").contains("단가를 찾지 못한 인스턴스 1개");
        java.util.List<String> notes = new java.util.ArrayList<>();
        body.at("/notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).as("겹치는 문구는 한 번만").doesNotHaveDuplicates();
    }

    @Test
    void 월말_예상이_0원이면_서버_수가_아니라_금액_때문에_비교하지_않았다고_안내한다() throws Exception {
        // 9/24~9/30에만 켜져 있었다: 최근 14일 사용량은 있지만 이번 달(10월) 비용이 없어 월말 예상이 0원이다
        aws.instances.add(Fixtures.instance("i-web", "t3.medium", "stopped", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-web", WINDOW_START, 156, 50, 90); // 9/30 24시(KST)까지

        JsonNode body = benchmark("");

        JsonNode spend = body.at("/monthlySpend");
        assertThat(spend.at("/status").asText()).isEqualTo("NOT_COMPARABLE");
        assertThat(body.at("/notes").toString()).contains("월말 예상 비용이 0원이라").doesNotContain("팀으로 학습해 서버");
    }

    @Test
    void 추천을_주면_적용_후_위치도_준다() throws Exception {
        threeServers();
        JsonNode body = benchmark("&recommendationIds=i-batch:IDLE,i-api:OVERSIZED");

        assertThat(body.at("/appliedRecommendationIds").size()).isEqualTo(2);
        JsonNode after = body.at("/oversizedRatio/afterSimulation");
        assertThat(after.at("/judgedCount").asInt()).as("중지한 batch는 빠진다").isEqualTo(2);
        assertThat(after.at("/oversizedCount").asInt()).isZero();
        assertThat(body.at("/monthlySpend/afterSimulation/monthlyCost").decimalValue())
                .as("197.904 − 118.26").isEqualByComparingTo("79.64");
        assertThat(body.at("/monthlySpend/afterSimulation/position").asText()).as("범위 하한 $108.36 아래").isEqualTo("BELOW");
        assertThat(body.at("/monthlySpend/afterSimulation/monthlyCostUsd").decimalValue()).isEqualByComparingTo("79.64");
        assertThat(body.at("/monthlySpend/afterSimulation/barPositionPercent").decimalValue()).isEqualByComparingTo("0");
        assertThat(body.at("/oversizedRatio/distribution/afterSimulationBucketIndex").asInt()).as("적용 후 0%").isZero();
        assertThat(body.at("/oversizedRatio/distribution/buckets/0/afterSimulation").asBoolean()).isTrue();
        assertThat(body.at("/selectedMonthlySavings").decimalValue())
                .as("시뮬레이션 selectedMonthlySavings와 같은 값 (SimulationApiTest 같은 서버 구성)").isEqualByComparingTo("118.26");

        getJson("/api/v1/aws/accounts/" + accountId + "/benchmark?recommendationIds=i-web:IDLE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_RECOMMENDATION"));
    }

    @Test
    void 전체_낭비_비율에_방치_리소스를_더하고_추천_적용_후도_준다() throws Exception {
        threeServers();
        aws.regionResources.put("ap-northeast-2", List.of(new AwsResource(ResourceType.ADDRESS, "eipalloc-free", null,
                "ap-northeast-2", null, null, false, null, null, null)));

        JsonNode w = benchmark("&recommendationIds=i-batch:IDLE,i-api:OVERSIZED").at("/wasteRatio");
        assertThat(w.at("/status").asText()).isEqualTo("OK");
        assertThat(w.at("/amounts/serverWaste").decimalValue()).isEqualByComparingTo("118.26");
        assertThat(w.at("/amounts/resourceWaste").decimalValue()).as("고정 IP $0.005 × 730").isEqualByComparingTo("3.65");
        assertThat(w.at("/amounts/total").decimalValue()).as("월말 예상 + 방치 리소스").isEqualByComparingTo("201.55");
        assertThat(w.at("/ratioPercent").decimalValue()).as("121.91 ÷ 201.554").isEqualByComparingTo("60.5");
        assertThat(w.at("/afterSimulationRatioPercent").decimalValue())
                .as("서버 낭비를 모두 적용하면 고정 IP만 남는다: 3.65 ÷ (79.64 + 3.65)").isEqualByComparingTo("4.4");
    }

    @Test
    void 권한이_부족하거나_단가를_모르면_전체_낭비_비율은_사유와_함께_계산하지_않는다() throws Exception {
        threeServers();
        aws.resourceError = new AwsAccessException(AwsAccessException.Category.PERMISSION_DENIED, "UnauthorizedOperation",
                "ec2:DescribeRegions", "권한이 없습니다.");
        JsonNode denied = benchmark("").at("/wasteRatio");
        assertThat(denied.at("/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(denied.at("/reason").asText()).startsWith("권한 업데이트가 필요해요");
        assertThat(denied.at("/ratioPercent").isNull()).isTrue();
        assertThat(denied.at("/referenceAverage").decimalValue()).isEqualByComparingTo("29");

        aws.resourceError = null;
        aws.regionResources.put("ap-northeast-2", List.of(new AwsResource(ResourceType.VOLUME, "vol-odd", null,
                "ap-northeast-2", "future-type", 10, false, Instant.parse("2026-09-01T00:00:00Z"), null, null)));
        clock.set(clock.instant().plus(java.time.Duration.ofHours(1)));
        JsonNode unknown = benchmark("").at("/wasteRatio");
        assertThat(unknown.at("/status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unknown.at("/reason").asText()).contains("단가를 모르는");
    }

    @Test
    void 서버_2대는_1_2대_구간과_견준다() throws Exception {
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);

        JsonNode body = benchmark("");
        JsonNode oversized = body.at("/oversizedRatio");

        assertThat(body.at("/status").asText()).isEqualTo("OK");
        assertThat(oversized.at("/status").asText()).isEqualTo("OK");
        assertThat(oversized.at("/ours/ratio").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(oversized.at("/matchedSizeBand").asText()).isEqualTo("1-2");
        assertThat(oversized.at("/reference/sizeBand").asText()).isEqualTo("1-2");
        assertThat(oversized.at("/reference/sizeBandLabel").asText()).isEqualTo("서버 1~2대");
        assertThat(oversized.at("/reference/dataAvailable").asBoolean()).isTrue();
        assertThat(oversized.at("/reference/sampleSize").asInt()).isEqualTo(1473);
        assertThat(oversized.at("/moreEfficientTeamsPercent").decimalValue())
                .as("1~2대 팀의 45%까지는 과다 사양 0%").isEqualByComparingTo("45.0");
        assertThat(oversized.at("/sizeBands").size()).isEqualTo(2);
        assertThat(oversized.at("/sizeBands/0/matchesOurs").asBoolean()).isTrue();
        assertThat(oversized.at("/sizeBands/1/matchesOurs").asBoolean()).isFalse();
        assertThat(body.at("/monthlySpend/status").asText()).isEqualTo("OK");
        assertThat(body.at("/monthlySpend/range/label").asText()).isEqualTo("서버 2대");
        assertThat(body.at("/monthlySpend/monthlyVcpuHours").decimalValue())
                .as("2vCPU 2대 한 달 내내 = 2880 → 서버 2대 표에서 확실히 적은 가장 큰 백분위 40% (2876.8)").isEqualByComparingTo("2880.0");
        assertThat(body.at("/monthlySpend/teamsUsingLessPercent").decimalValue()).isEqualByComparingTo("40.0");
        assertThat(body.at("/monthlySpend/position").asText()).as("25~75번째 1166.3~3462.8 안").isEqualTo("WITHIN");
    }

    @Test
    void 어느_구간에도_안_드는_서버_수는_비교하지_않는다() throws Exception {
        for (int i = 0; i < 21; i++) {
            server("i-w" + i, "t3.medium", 50, 90, 50L * 1024 * 1024);
        }

        JsonNode oversized = benchmark("").at("/oversizedRatio");

        assertThat(oversized.at("/status").asText()).isEqualTo("NOT_COMPARABLE");
        assertThat(oversized.at("/ours/judgedCount").asInt()).isEqualTo(21);
        assertThat(oversized.at("/matchedSizeBand").isNull()).isTrue();
        assertThat(oversized.at("/reference/dataAvailable").asBoolean()).isFalse();
        JsonNode spend = benchmark("").at("/monthlySpend");
        assertThat(spend.at("/status").asText()).as("월 지출 모델은 서버 1~50대를 학습해 21대도 비교한다").isEqualTo("OK");
        assertThat(spend.at("/range/label").asText()).isEqualTo("서버 21대");
    }

    @Test
    void 다른_규모_구간을_고르면_그_구간과_견준다() throws Exception {
        server("i-api", "m5.large", 6, 23, 10L * 1024 * 1024);
        server("i-web", "t3.medium", 50, 90, 50L * 1024 * 1024);

        JsonNode body = benchmark("&sizeBand=3-20");
        JsonNode oversized = body.at("/oversizedRatio");

        assertThat(oversized.at("/status").asText()).isEqualTo("OK");
        assertThat(oversized.at("/matchedSizeBand").asText()).as("우리 구간은 그대로").isEqualTo("1-2");
        assertThat(oversized.at("/reference/sizeBand").asText()).isEqualTo("3-20");
        assertThat(oversized.at("/reference/averageRatio").decimalValue()).isEqualByComparingTo("42");
        assertThat(body.at("/notes").toString()).contains("우리는 서버 2대지만 서버 3~20대 팀 자료와 견줬어요");
        assertThat(body.at("/monthlySpend/range/servers").asInt()).as("월 지출은 구간이 아니라 우리 서버 대수 그대로").isEqualTo(2);

        getJson("/api/v1/aws/accounts/" + accountId + "/benchmark?sizeBand=4-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 오래_꺼_둔_서버는_낭비_탐지처럼_판단하지_않는다() throws Exception {
        threeServers();
        // 9/24~9/29만 켜졌다가 지금 중지 → 낭비 탐지가 판단하지 않는 서버
        aws.instances.add(Fixtures.instance("i-old", "m5.large", "stopped", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addCpuHours("i-old", WINDOW_START, 5 * 24, 3, 5);

        JsonNode body = benchmark("");
        JsonNode waste = body(getJson("/api/v1/aws/accounts/" + accountId + "/waste").andExpect(status().isOk()));

        assertThat(body.at("/oversizedRatio/ours/judgedCount").asInt())
                .isEqualTo(waste.at("/judgedCount").asInt()).isEqualTo(3);
        assertThat(body.at("/oversizedRatio/servers").findValuesAsText("instanceId")).doesNotContain("i-old");
    }

    private static final String DEMO = """
        {"from":"%s","to":"2026-10-07","servers":[
          {"name":"api","instanceType":"m5.large","os":"LINUX","count":2,"schedule":"ALWAYS","usagePattern":"OVERSIZED"},
          {"name":"web","instanceType":"t3.medium","os":"LINUX","count":2,"schedule":"ALWAYS"}]}
        """;

    @Test
    void 체험_모드도_같은_규칙으로_비교하고_기록이_14일_미만이면_기록_부족이다() throws Exception {
        JsonNode body = body(mvc.perform(post("/api/v1/demo/benchmark?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(DEMO.formatted("2026-09-20")))
                .andExpect(status().isOk()));

        assertThat(body.at("/dataSource").asText()).isEqualTo("demo");
        assertThat(body.at("/oversizedRatio/ours/judgedCount").asInt()).isEqualTo(4);
        assertThat(body.at("/oversizedRatio/ours/ratio").decimalValue()).isEqualByComparingTo("50.0");

        JsonNode shortPeriod = body(mvc.perform(post("/api/v1/demo/benchmark")
                .contentType(MediaType.APPLICATION_JSON).content(DEMO.formatted("2026-10-01")))
                .andExpect(status().isOk()));
        assertThat(shortPeriod.at("/status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(shortPeriod.at("/availableAt").asText()).isEqualTo("2026-10-15");
        assertThat(shortPeriod.at("/oversizedRatio/status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(shortPeriod.at("/oversizedRatio/ours").isNull()).isTrue();
        assertThat(shortPeriod.at("/monthlySpend/status").asText())
                .as("사용량을 14일로 재므로 월 지출 순위도 기록 부족").isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(shortPeriod.at("/monthlySpend/monthlyCost").isNull()).as("금액은 보여 준다").isFalse();
        assertThat(body.at("/monthlySpend/status").asText()).isEqualTo("OK");
        assertThat(body.at("/monthlySpend/servers").asInt()).isEqualTo(4);
        assertThat(body.at("/wasteRatio/status").asText()).as("체험 모드 방치 리소스 예시를 더한다").isEqualTo("OK");
        assertThat(body.at("/wasteRatio/amounts/resourceWaste").decimalValue()).isPositive();
        assertThat(shortPeriod.at("/wasteRatio/status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
    }
}
