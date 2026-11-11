package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 방치 리소스·잊힌 리전 (ADR-020). NOW = 2026-10-08 03:00Z, 연결 리전 ap-northeast-2 */
class ResourceWasteApiTest extends IntegrationTestBase {

    private String accountId;

    private static Instant daysAgo(int days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.regionResources.put("ap-northeast-2", List.of(
            new AwsResource(ResourceType.VOLUME, "vol-free", "old-data", "ap-northeast-2", "gp3", 100, false,
                daysAgo(20), null, null),
            new AwsResource(ResourceType.ADDRESS, "eipalloc-free", null, "ap-northeast-2", null, null, false, null, null,
                null),
            new AwsResource(ResourceType.SNAPSHOT, "snap-old", null, "ap-northeast-2", null, 50, true, daysAgo(120),
                null, null),
            new AwsResource(ResourceType.LOAD_BALANCER, "lb-idle", "lb-idle", "ap-northeast-2", "application", null,
                true, daysAgo(30), 0L, null)));
        aws.regionResources.put("us-east-1", List.of(
            new AwsResource(ResourceType.INSTANCE, "i-far", "forgotten", "us-east-1", "t3.medium", null, true,
                daysAgo(90), null, "Linux/UNIX")));
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private JsonNode resources() throws Exception {
        return body(getJson("/api/v1/aws/accounts/" + accountId + "/resources/waste?currency=USD")
                .andExpect(status().isOk()));
    }

    @Test
    void 모든_리전을_훑어_다섯_종류를_찾고_잊힌_리전을_알려_준다() throws Exception {
        JsonNode body = resources();

        JsonNode r = body.at("/resources");
        assertThat(r.at("/scan/status").asText()).isEqualTo("OK");
        assertThat(r.at("/scan/permissionVersion").asInt()).isEqualTo(2);
        assertThat(r.at("/costBasis").asText()).isEqualTo("estimated_list_price");
        assertThat(r.at("/items").findValuesAsText("kind")).containsExactly(
                "FORGOTTEN_REGION", "IDLE_LB", "UNATTACHED_VOLUME", "UNUSED_EIP", "OLD_SNAPSHOT");
        assertThat(r.at("/items").findValuesAsText("resourceTypeLabel"))
                .containsExactly("서버", "로드밸런서", "볼륨", "고정 IP", "스냅샷");
        assertThat(r.at("/items/0/region").asText()).isEqualTo("us-east-1");
        assertThat(r.at("/items/0/otherRegion").asBoolean()).isTrue();
        assertThat(r.at("/items/2/name").asText()).isEqualTo("old-data");
        // 30.37 + 16.43 + 9.12 + 3.65 + 2.50
        assertThat(r.at("/totalMonthlyCost").decimalValue()).isEqualByComparingTo("62.07");
        assertThat(r.at("/otherRegions/0/region").asText()).isEqualTo("us-east-1");
        assertThat(r.at("/otherRegions/0/runningServers").asInt()).isEqualTo(1);
        assertThat(r.at("/upgrade").isNull()).isTrue();
        assertThat(aws.regionScans).isEqualTo(2);
    }

    @Test
    void 낭비_목록_응답에도_방치_리소스를_함께_준다() throws Exception {
        JsonNode body = body(getJson("/api/v1/aws/accounts/" + accountId + "/waste?currency=USD")
                .andExpect(status().isOk()));

        assertThat(body.at("/resources/findingCount").asInt()).isEqualTo(5);
        assertThat(body.at("/resources/items/0/resourceType").asText()).isEqualTo("INSTANCE");
    }

    @Test
    void 결과는_6시간_동안_다시_훑지_않는다() throws Exception {
        resources();
        resources();
        assertThat(aws.regionScans).isEqualTo(2);

        clock.set(NOW.plus(Duration.ofHours(6)));
        resources();
        assertThat(aws.regionScans).isEqualTo(4);
    }

    @Test
    void 이전_템플릿이라_권한이_없으면_오류_대신_스택_업데이트_방법을_준다() throws Exception {
        aws.resourceError = new AwsAccessException(Category.PERMISSION_DENIED, "UnauthorizedOperation",
                "ec2:DescribeRegions", "권한이 없습니다.");

        JsonNode r = resources().at("/resources");
        assertThat(r.at("/scan/status").asText()).isEqualTo("PERMISSION_UPGRADE_REQUIRED");
        assertThat(r.at("/scan/permissionVersion").asInt()).isEqualTo(1);
        assertThat(r.at("/scan/message").asText()).contains("권한 업데이트");
        assertThat(r.at("/upgrade/stackName").asText()).startsWith("CloudCost");
        assertThat(r.at("/upgrade/templateUrl").asText()).endsWith("customer-readonly-role.yaml");
        assertThat(r.at("/upgrade/consoleUrl").asText()).contains("cloudformation").contains("ap-northeast-2");
        assertThat(r.at("/items").size()).isZero();
        assertThat(r.at("/notes/0").asText()).startsWith("권한 업데이트 필요");

        // EC2 낭비 목록과 연결 상태는 그대로다
        getJson("/api/v1/aws/accounts/" + accountId + "/waste").andExpect(status().isOk());
        assertThat(body(getJson("/api/v1/aws/accounts/" + accountId)).toString()).contains("ACTIVE");

        // 스택을 업데이트하면 1시간 안에 다시 확인해 복구한다
        aws.resourceError = null;
        clock.set(NOW.plus(Duration.ofHours(1)));
        assertThat(resources().at("/resources/scan/status").asText()).isEqualTo("OK");
    }

    @Test
    void 남의_연결은_404이고_연결을_지우면_리소스도_지운다() throws Exception {
        mvc.perform(as("someone-else", get("/api/v1/aws/accounts/" + accountId + "/resources/waste")))
                .andExpect(status().isNotFound());
        assertThat(aws.regionScans).isZero();

        resources();
        call(delete("/api/v1/aws/accounts/" + accountId)).andExpect(status().isOk());
        getJson("/api/v1/aws/accounts/" + accountId + "/resources/waste").andExpect(status().isNotFound());
    }

    @Test
    void 체험_모드에도_방치_리소스_예시가_있다() throws Exception {
        String setup = """
            {"from":"2026-09-20","to":"2026-10-07",
             "servers":[{"name":"web","instanceType":"t3.medium","os":"LINUX","count":1,"schedule":"ALWAYS"}]}
            """;
        JsonNode body = body(mvc.perform(post("/api/v1/demo/waste?currency=USD")
                .contentType(MediaType.APPLICATION_JSON).content(setup)).andExpect(status().isOk()));

        assertThat(body.at("/resources/items").findValuesAsText("kind")).containsExactlyInAnyOrder(
                "FORGOTTEN_REGION", "IDLE_LB", "UNATTACHED_VOLUME", "UNUSED_EIP", "OLD_SNAPSHOT");
        assertThat(aws.assumeCalls).isZero();
    }
}
