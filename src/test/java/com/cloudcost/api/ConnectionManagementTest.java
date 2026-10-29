package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.StackCallbackService;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.application.port.SyncJobRepository;
import com.cloudcost.domain.account.StackCallback;
import com.cloudcost.domain.account.StackCallback.RequestType;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 연결 이름 · 연결 취소/삭제 · 목록 보기 (이슈 #15) */
class ConnectionManagementTest extends IntegrationTestBase {

    private static final String STACK_ID =
            "arn:aws:cloudformation:ap-northeast-2:123456789012:stack/CloudCostInsight/1f2e3d4c";

    @Autowired Ec2InstanceRepository instances;
    @Autowired Ec2UsageRepository usage;
    @Autowired SyncJobRepository syncJobs;
    @Autowired StackCallbackService callbacks;

    private ResultActions create(String body) throws Exception {
        return call(post("/api/v1/aws/accounts").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions rename(String accountId, String body) throws Exception {
        return call(patch("/api/v1/aws/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 연결 이름 ----

    @Test
    void 연결을_시작할_때_이름을_붙이고_앞뒤_공백은_지운다() throws Exception {
        create("{\"region\":\"ap-northeast-2\",\"alias\":\"  개인 계정  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.account.alias").value("개인 계정"));
    }

    @Test
    void 이름_없이_시작하면_alias는_null() throws Exception {
        create("{\"region\":\"ap-northeast-2\"}").andExpect(jsonPath("$.account.alias").doesNotExist());
        create("{\"region\":\"ap-northeast-2\",\"alias\":\"   \"}").andExpect(jsonPath("$.account.alias").doesNotExist());
    }

    @Test
    void 이름을_바꾸고_비우면_지운다() throws Exception {
        String id = createAccount();
        rename(id, "{\"alias\":\"캡스톤 팀 계정\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alias").value("캡스톤 팀 계정"));
        getJson("/api/v1/aws/accounts/" + id).andExpect(jsonPath("$.account.alias").value("캡스톤 팀 계정"));

        rename(id, "{\"alias\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.alias").doesNotExist());
    }

    @Test
    void 이름은_40자까지이고_넘거나_제어_문자가_있으면_400() throws Exception {
        String id = createAccount();
        rename(id, "{\"alias\":\"" + "가".repeat(40) + "\"}").andExpect(status().isOk());
        rename(id, "{\"alias\":\"" + "가".repeat(41) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ALIAS"));
        rename(id, "{\"alias\":\"줄\\n바꿈\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ALIAS"));
        create("{\"alias\":\"" + "a".repeat(41) + "\"}").andExpect(status().isBadRequest());
        getJson("/api/v1/aws/accounts/" + id).andExpect(jsonPath("$.account.alias").value("가".repeat(40)));
    }

    // ---- 목록 ----

    @Test
    void 목록은_1시간_넘은_PENDING을_숨기고_includePending이면_모두_보여_준다() throws Exception {
        String abandoned = createAccount();
        clock.advance(Duration.ofMinutes(61));
        String recent = createAccount();
        String active = activeAccount();

        JsonNode list = body(getJson("/api/v1/aws/accounts").andExpect(status().isOk()));
        assertThat(list.findValuesAsText("accountId")).containsExactlyInAnyOrder(recent, active);

        JsonNode all = body(getJson("/api/v1/aws/accounts?includePending=true"));
        assertThat(all.findValuesAsText("accountId")).containsExactlyInAnyOrder(abandoned, recent, active);
    }

    @Test
    void 목록에_한국어_리전_이름과_스택_이름이_있다() throws Exception {
        create("{\"region\":\"us-west-2\",\"alias\":\"오레곤 테스트\"}");
        String seoul = createAccount();

        JsonNode list = body(getJson("/api/v1/aws/accounts"));
        for (JsonNode a : list) {
            if (a.get("accountId").asText().equals(seoul)) {
                assertThat(a.get("regionLabel").asText()).isEqualTo("서울");
                assertThat(a.get("stackName").asText()).isEqualTo("CloudCostInsight-" + seoul.substring(0, 8));
            } else {
                assertThat(a.get("regionLabel").asText()).isEqualTo("오레곤");
                assertThat(a.get("alias").asText()).isEqualTo("오레곤 테스트");
            }
        }
        assertThat(list.size()).isEqualTo(2);
    }

    // ---- 삭제 ----

    @Test
    void ACTIVE_연결을_삭제하면_수집_기록까지_모두_지우고_스택_삭제를_안내한다() throws Exception {
        aws.instances.add(Fixtures.instance("i-a", "t3.micro", "running", Instant.parse("2026-10-01T00:00:00Z")));
        aws.addHours("i-a", Instant.parse("2026-10-07T00:00:00Z"), 5, 12);
        String id = activeAccount();
        UUID accountId = UUID.fromString(id);
        getJson("/api/v1/aws/accounts/" + id + "/costs").andExpect(status().isOk());
        assertThat(instances.findAllByOwnerIdAndAccountId(owner, accountId)).isNotEmpty();
        assertThat(usage.findHourly(owner, accountId, Instant.EPOCH, NOW.plusSeconds(3600))).isNotEmpty();
        assertThat(syncJobs.findRecentByOwnerIdAndAccountId(owner, accountId, 10)).isNotEmpty();

        call(delete("/api/v1/aws/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.awsCleanupNeeded").value(true))
                .andExpect(jsonPath("$.stackName").value("CloudCostInsight-" + id.substring(0, 8)))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "CloudFormation에서 'CloudCostInsight-" + id.substring(0, 8) + "' 스택을 삭제하세요")));

        assertThat(instances.findAllByOwnerIdAndAccountId(owner, accountId)).isEmpty();
        assertThat(usage.findHourly(owner, accountId, Instant.EPOCH, NOW.plusSeconds(3600))).isEmpty();
        assertThat(syncJobs.findRecentByOwnerIdAndAccountId(owner, accountId, 10)).isEmpty();
        getJson("/api/v1/aws/accounts/" + id).andExpect(status().isNotFound());
        getJson("/api/v1/aws/accounts?includePending=true").andExpect(jsonPath("$.length()").value(0));
        call(delete("/api/v1/aws/accounts/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void PENDING_연결을_취소하면_스택_정리가_필요_없다() throws Exception {
        String id = createAccount();
        call(delete("/api/v1/aws/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("PENDING"))
                .andExpect(jsonPath("$.awsCleanupNeeded").value(false))
                .andExpect(jsonPath("$.message").value("연결을 취소했습니다."));
    }

    @Test
    void 삭제한_뒤_같은_AWS_계정을_다시_연결할_수_있다() throws Exception {
        String first = activeAccount();
        call(delete("/api/v1/aws/accounts/" + first)).andExpect(status().isOk());
        String second = createAccount();
        registerRole(second, Fixtures.CUSTOMER_ROLE_ARN).andExpect(status().isOk());
    }

    @Test
    void 남의_연결은_이름_변경과_삭제_모두_404이고_아무것도_바뀌지_않는다() throws Exception {
        aws.instances.add(Fixtures.instance("i-a", "t3.micro", "running", Instant.parse("2026-10-01T00:00:00Z")));
        String id = activeAccount();
        getJson("/api/v1/aws/accounts/" + id + "/instances").andExpect(status().isOk());
        rename(id, "{\"alias\":\"내 계정\"}");
        String userB = "user-b-" + owner;

        mvc.perform(as(userB, patch("/api/v1/aws/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"가로채기\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(as(userB, delete("/api/v1/aws/accounts/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(as(userB, get("/api/v1/aws/accounts?includePending=true")))
                .andExpect(jsonPath("$.length()").value(0));

        getJson("/api/v1/aws/accounts/" + id)
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.account.alias").value("내 계정"));
        assertThat(instances.findAllByOwnerIdAndAccountId(owner, UUID.fromString(id))).isNotEmpty();
    }

    // ---- 삭제 뒤 늦게 온 스택 신호 ----

    @Test
    void 삭제한_연결의_스택_삭제_신호는_무시하고_SUCCESS로_응답한다() throws Exception {
        JsonNode setup = body(getJson("/api/v1/aws/accounts/" + createAccount()));
        String id = setup.at("/account/accountId").asText();
        String externalId = setup.at("/account/externalId").asText();
        callbacks.handle(callback(RequestType.CREATE, id, externalId));
        assertThat(cfnResponses.last().success()).isTrue();
        call(delete("/api/v1/aws/accounts/" + id)).andExpect(jsonPath("$.awsCleanupNeeded").value(true));

        callbacks.handle(callback(RequestType.DELETE, id, externalId));

        assertThat(cfnResponses.last().success()).as("고객의 스택 삭제를 막지 않는다").isTrue();
        getJson("/api/v1/aws/accounts?includePending=true").andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 취소한_연결의_늦은_스택_생성_신호는_FAILED로_응답해_되살리지_않는다() throws Exception {
        JsonNode setup = body(getJson("/api/v1/aws/accounts/" + createAccount()));
        String id = setup.at("/account/accountId").asText();
        String externalId = setup.at("/account/externalId").asText();
        call(delete("/api/v1/aws/accounts/" + id)).andExpect(status().isOk());

        callbacks.handle(callback(RequestType.CREATE, id, externalId));

        assertThat(cfnResponses.last().success()).as("고객 스택이 롤백되어 Role이 남지 않는다").isFalse();
        assertThat(aws.assumeCalls).isZero();
        getJson("/api/v1/aws/accounts?includePending=true").andExpect(jsonPath("$.length()").value(0));
    }

    private StackCallback callback(RequestType type, String connectionId, String externalId) {
        return new StackCallback(type, connectionId, externalId, Fixtures.CUSTOMER_ROLE_ARN, STACK_ID, "req-1",
                "ConnectionNotice", type == RequestType.CREATE ? null : "cci-connection-" + connectionId,
                "https://cloudformation-custom-resource-response-apnortheast2.s3.ap-northeast-2.amazonaws.com/x");
    }
}
