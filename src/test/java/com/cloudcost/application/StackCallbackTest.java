package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.domain.account.StackCallback;
import com.cloudcost.domain.account.StackCallback.RequestType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 빠른 생성 링크 → 고객 스택이 SNS로 보낸 신호 → 실제(가짜 어댑터) AssumeRole 검증 → CloudFormation 응답 */
class StackCallbackTest extends IntegrationTestBase {

    private static final String ROLE_ARN = "arn:aws:iam::123456789012:role/CloudCostReadOnlyRole";
    private static final String STACK_ID =
            "arn:aws:cloudformation:ap-northeast-2:123456789012:stack/CloudCostInsight/1f2e3d4c";

    @Autowired StackCallbackService callbacks;

    private JsonNode setup;

    private String start() throws Exception {
        setup = body(getJson("/api/v1/aws/accounts/" + createAccount()));
        return setup.at("/account/accountId").asText();
    }

    private StackCallback callback(RequestType type, String connectionId, String externalId, String roleArn) {
        return new StackCallback(type, connectionId, externalId, roleArn, STACK_ID, "req-1", "ConnectionNotice",
                type == RequestType.CREATE ? null : "cci-connection-" + connectionId,
                "https://cloudformation-custom-resource-response-apnortheast2.s3.ap-northeast-2.amazonaws.com/x");
    }

    @Test
    void 연결_시작_응답에_값이_채워진_빠른_생성_링크가_있다() throws Exception {
        String id = start();
        String externalId = setup.at("/account/externalId").asText();
        String link = URLDecoder.decode(setup.at("/roleSetup/quickCreateUrl").asText(), StandardCharsets.UTF_8);

        assertThat(link).startsWith("https://ap-northeast-2.console.aws.amazon.com/cloudformation/home?region=ap-northeast-2#/stacks/quickcreate?")
                .contains("templateURL=https://cci-templates-111122223333.s3.ap-northeast-2.amazonaws.com/customer-readonly-role.yaml")
                .contains("stackName=CloudCostInsight")
                .contains("param_ExternalId=" + externalId)
                .contains("param_ConnectionId=" + id)
                .contains("param_TrustedPrincipalArn=arn:aws:iam::111122223333:role/CloudCostInsightBackendRole")
                .contains("param_CallbackTopicArn=arn:aws:sns:ap-northeast-2:111122223333:cloudcost-insight-connections")
                .contains("param_MonitoredRegion=ap-northeast-2");
        assertThat(setup.at("/roleSetup/steps/0").asText()).contains("quickCreateUrl");
        assertThat(setup.at("/roleSetup/warnings")).isEmpty();
    }

    @Test
    void 스택_생성_신호를_받으면_실제_AssumeRole로_검증해_ACTIVE로_만들고_SUCCESS로_응답한다() throws Exception {
        String id = start();
        String externalId = setup.at("/account/externalId").asText();

        callbacks.handle(callback(RequestType.CREATE, id, externalId, ROLE_ARN));

        assertThat(cfnResponses.last().success()).isTrue();
        assertThat(aws.lastExternalId.value()).isEqualTo(externalId);
        assertThat(aws.verifyCalls).isEqualTo(1);
        getJson("/api/v1/aws/accounts/" + id)
                .andExpect(jsonPath("$.account.status").value("ACTIVE"))
                .andExpect(jsonPath("$.account.roleArn").value(ROLE_ARN))
                .andExpect(jsonPath("$.account.stackId").value(STACK_ID));
        getJson("/api/v1/aws/accounts/" + id + "/instances").andExpect(status().isOk());
    }

    @Test
    void 막_만든_Role이_IAM에_반영될_때까지_재시도한다() throws Exception {
        String id = start();
        aws.assumeError = new AwsAccessException(Category.ASSUME_ROLE_DENIED, "AccessDenied", "sts:AssumeRole", "AccessDenied");
        aws.assumeFailuresBeforeSuccess = 2;

        callbacks.handle(callback(RequestType.CREATE, id, setup.at("/account/externalId").asText(), ROLE_ARN));

        assertThat(aws.assumeCalls).isEqualTo(3);
        assertThat(cfnResponses.last().success()).isTrue();
        getJson("/api/v1/aws/accounts/" + id).andExpect(jsonPath("$.account.status").value("ACTIVE"));
    }

    @Test
    void 끝내_검증하지_못하면_FAILED로_응답해_고객_스택이_롤백된다() throws Exception {
        String id = start();
        aws.assumeError = new AwsAccessException(Category.ASSUME_ROLE_DENIED, "AccessDenied", "sts:AssumeRole",
                "AccessDenied: Role을 맡을 수 없습니다.");

        callbacks.handle(callback(RequestType.CREATE, id, setup.at("/account/externalId").asText(), ROLE_ARN));

        assertThat(aws.assumeCalls).isEqualTo(6);
        assertThat(cfnResponses.last().success()).isFalse();
        assertThat(cfnResponses.last().reason()).contains("Role을 맡을 수 없습니다");
        getJson("/api/v1/aws/accounts/" + id).andExpect(jsonPath("$.account.status").value("ERROR"));
    }

    @Test
    void 연결_id나_External_ID가_맞지_않는_신호는_AWS를_부르지_않고_FAILED() throws Exception {
        String id = start();
        callbacks.handle(callback(RequestType.CREATE, id, "cci-000000000000000000000000", ROLE_ARN));
        assertThat(cfnResponses.last().success()).isFalse();

        callbacks.handle(callback(RequestType.CREATE, "11111111-1111-1111-1111-111111111111",
                setup.at("/account/externalId").asText(), ROLE_ARN));
        assertThat(cfnResponses.last().success()).isFalse();
        assertThat(aws.assumeCalls).isZero();
        getJson("/api/v1/aws/accounts/" + id).andExpect(jsonPath("$.account.status").value("PENDING"));
    }

    @Test
    void Role이_스택과_다른_계정에_있으면_FAILED() throws Exception {
        String id = start();
        callbacks.handle(callback(RequestType.CREATE, id, setup.at("/account/externalId").asText(),
                "arn:aws:iam::999999999999:role/CloudCostReadOnlyRole"));
        assertThat(cfnResponses.last().success()).isFalse();
        assertThat(aws.assumeCalls).isZero();
    }

    @Test
    void 스택을_삭제하면_DISCONNECTED가_되고_조회는_409로_안내한다() throws Exception {
        String id = start();
        String externalId = setup.at("/account/externalId").asText();
        callbacks.handle(callback(RequestType.CREATE, id, externalId, ROLE_ARN));

        callbacks.handle(callback(RequestType.DELETE, id, externalId, ROLE_ARN));

        assertThat(cfnResponses.last().success()).as("스택 삭제는 막지 않는다").isTrue();
        getJson("/api/v1/aws/accounts/" + id)
                .andExpect(jsonPath("$.account.status").value("DISCONNECTED"))
                .andExpect(jsonPath("$.account.disconnectedAt").exists());
        getJson("/api/v1/aws/accounts/" + id + "/costs")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISCONNECTED"));
    }

    @Test
    void 모르는_연결의_삭제_신호도_SUCCESS로_응답한다() {
        callbacks.handle(callback(RequestType.DELETE, "11111111-1111-1111-1111-111111111111",
                "cci-000000000000000000000000", ROLE_ARN));
        assertThat(cfnResponses.last().success()).isTrue();
    }

    @Test
    void 템플릿을_내려받을_수_있다() throws Exception {
        String yaml = getJson("/api/v1/aws/cloudformation-template")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(yaml).contains("Custom::CloudCostInsightConnection");
    }
}
