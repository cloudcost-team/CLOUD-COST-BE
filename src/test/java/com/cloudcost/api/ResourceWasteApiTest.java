package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceScan;
import com.cloudcost.domain.resource.ResourceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** 방치 리소스·잊힌 리전 (ADR-020). NOW = 2026-10-08 03:00Z, 연결 리전 ap-northeast-2 */
class ResourceWasteApiTest extends IntegrationTestBase {

    @Autowired AwsAccountRepository accounts;
    @Autowired AccountResourceRepository resourceRepository;

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
        // 빠른 생성 스택은 SNS 토픽 리전(여기서는 수집 리전과 다른 us-east-1)에 만들어진다
        AwsAccount account = accounts.findByOwnerIdAndId(owner, UUID.fromString(accountId)).orElseThrow();
        account.linkStack("arn:aws:cloudformation:us-east-1:123456789012:stack/CloudCostInsight-abc/1f2e3d4c", NOW);
        accounts.save(account);
        aws.resourceError = new AwsAccessException(Category.PERMISSION_DENIED, "UnauthorizedOperation",
                "ec2:DescribeRegions", "권한이 없습니다.");

        JsonNode r = resources().at("/resources");
        assertThat(r.at("/scan/status").asText()).isEqualTo("PERMISSION_UPGRADE_REQUIRED");
        assertThat(r.at("/scan/permissionVersion").asInt()).isEqualTo(1);
        // v1이라고 단정하지 않고 거부 정책 가능성도 알린다
        assertThat(r.at("/scan/message").asText()).contains("ec2:DescribeRegions").contains("스택을 업데이트")
                .contains("SCP");
        assertThat(r.at("/upgrade/method").asText()).isEqualTo("STACK");
        assertThat(r.at("/upgrade/stackName").asText()).isEqualTo("CloudCostInsight-abc");
        assertThat(r.at("/upgrade/templateUrl").asText()).endsWith("customer-readonly-role.yaml");
        assertThat(r.at("/upgrade/consoleUrl").asText()).contains("cloudformation").contains("region=us-east-1");
        assertThat(r.at("/upgrade/permissionPolicy").isNull()).isTrue();
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
    void 직접_만든_Role이면_스택_대신_Role_정책_수정_방법을_준다() throws Exception {
        aws.resourceError = new AwsAccessException(Category.PERMISSION_DENIED, "UnauthorizedOperation",
                "ec2:DescribeVolumes", "권한이 없습니다.");

        JsonNode u = resources().at("/resources/upgrade");
        assertThat(u.at("/method").asText()).isEqualTo("MANUAL_ROLE");
        assertThat(u.at("/stackName").isNull()).isTrue();
        assertThat(u.at("/consoleUrl").asText()).contains("/iam/");
        assertThat(u.at("/permissionPolicy/Statement/0/Action").toString())
                .contains("ec2:DescribeVolumes").contains("elasticloadbalancing:DescribeLoadBalancers");
    }

    @Test
    void 전에_확인된_권한이_거부되면_거부_정책을_확인하라고_안내한다() throws Exception {
        resources();
        aws.resourceError = new AwsAccessException(Category.PERMISSION_DENIED, "AccessDenied",
                "ec2:DescribeRegions", "권한이 없습니다.");
        clock.set(NOW.plus(Duration.ofHours(6)));

        String message = resources().at("/resources/scan/message").asText();
        assertThat(message).contains("전에는 확인됐던 권한").contains("SCP");
    }

    @Test
    void 확장_수집의_호출_수를_실패해도_남긴다() throws Exception {
        aws.lbMetricCalls = 3;
        resources();
        ResourceScan ok = resourceRepository.findScanByOwnerIdAndAccountId(owner, UUID.fromString(accountId))
                .orElseThrow();
        assertThat(ok.describeCount()).isEqualTo(10);
        assertThat(ok.cloudWatchCount()).isEqualTo(3);

        aws.regionErrors.put("us-east-1", new AwsAccessException(Category.THROTTLED, "Throttling",
                "ec2:DescribeVolumes", "요청이 너무 많아요."));
        clock.set(NOW.plus(Duration.ofHours(6)));
        assertThat(resources().at("/resources/scan/status").asText()).isEqualTo("FAILED");
        ResourceScan failed = resourceRepository.findScanByOwnerIdAndAccountId(owner, UUID.fromString(accountId))
                .orElseThrow();
        assertThat(failed.describeCount()).isEqualTo(10);
        assertThat(failed.cloudWatchCount()).isEqualTo(3);
    }

    @Test
    void 단가를_모르는_항목이_있으면_합계를_내지_않는다() throws Exception {
        aws.regionResources.put("ap-northeast-2", List.of(
            new AwsResource(ResourceType.VOLUME, "vol-odd", null, "ap-northeast-2", "future-type", 10, false,
                daysAgo(20), null, null),
            new AwsResource(ResourceType.ADDRESS, "eipalloc-free", null, "ap-northeast-2", null, null, false, null, null,
                null)));
        aws.regionResources.remove("us-east-1");

        JsonNode r = resources().at("/resources");
        assertThat(r.at("/totalMonthlyCost").isNull()).isTrue();
        assertThat(r.at("/items/0/monthlyCost").isNull() || r.at("/items/1/monthlyCost").isNull()).isTrue();
        assertThat(r.at("/items").findValuesAsText("priceNote")).anyMatch(n -> n.contains("future-type"));
        assertThat(r.at("/notes").toString()).contains("합계를 내지 않았어요");
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
