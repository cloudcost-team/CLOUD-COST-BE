package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** AWS 연결: External ID 발급 → Role ARN 등록 → 실제(가짜 어댑터) AssumeRole 검증 */
class AwsConnectionTest extends IntegrationTestBase {

    @Test
    void 연결_시작하면_External_ID와_붙여_넣을_정책을_발급하고_PENDING이다() throws Exception {
        JsonNode body = body(call(post("/api/v1/aws/accounts")).andExpect(status().isCreated()));

        assertThat(body.at("/account/status").asText()).isEqualTo("PENDING");
        String externalId = body.at("/account/externalId").asText();
        assertThat(externalId).matches("^cci-[0-9a-f]{24}$");
        assertThat(body.at("/account/region").asText()).isEqualTo("ap-northeast-2");
        assertThat(body.at("/roleSetup/requiredActions").toString())
                .isEqualTo("[\"ec2:DescribeInstances\",\"cloudwatch:GetMetricStatistics\",\"ec2:DescribeRegions\","
                        + "\"ec2:DescribeVolumes\",\"ec2:DescribeAddresses\",\"ec2:DescribeSnapshots\","
                        + "\"elasticloadbalancing:DescribeLoadBalancers\"]");
        assertThat(body.at("/roleSetup/trustPolicy/Statement/0/Condition/StringEquals/sts:ExternalId").asText())
                .isEqualTo(externalId);
        assertThat(body.at("/roleSetup/trustPolicy/Statement/0/Principal/AWS").asText())
                .isEqualTo("arn:aws:iam::111122223333:role/CloudCostInsightBackendRole");
        assertThat(aws.assumeCalls).as("발급 단계에서는 AWS를 부르지 않는다").isZero();
    }

    @Test
    void Role_ARN을_등록하면_발급한_External_ID로_AssumeRole하고_DescribeInstances_1건으로_검증한다() throws Exception {
        String id = createAccount();
        String externalId = body(getJson("/api/v1/aws/accounts/" + id)).at("/account/externalId").asText();

        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.awsAccountId").value("123456789012"))
                .andExpect(jsonPath("$.verifiedAt").exists());

        assertThat(aws.assumeCalls).isEqualTo(1);
        assertThat(aws.lastExternalId.value()).isEqualTo(externalId);
        assertThat(aws.verifyCalls).isEqualTo(1);
        assertThat(aws.describeCalls).isZero();
        assertThat(aws.cloudWatchCalls).as("연결 검증에서는 CloudWatch를 부르지 않는다").isZero();
        assertThat(aws.openSessions).as("세션은 닫혀야 한다").isZero();
    }

    @Test
    void AssumeRole이_거부되면_ERROR로_기록하고_원인을_알려_준다() throws Exception {
        String id = createAccount();
        aws.assumeError = new AwsAccessException(Category.ASSUME_ROLE_DENIED, "AccessDenied", "sts:AssumeRole",
                "AccessDenied: Role을 맡을 수 없습니다.");

        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AWS_ASSUME_ROLE_FAILED"))
                .andExpect(jsonPath("$.error.message").value("AccessDenied: Role을 맡을 수 없습니다."))
                .andExpect(jsonPath("$.error.details.action").value("sts:AssumeRole"));

        getJson("/api/v1/aws/accounts/" + id)
                .andExpect(jsonPath("$.account.status").value("ERROR"))
                .andExpect(jsonPath("$.account.lastError.code").value("ASSUME_ROLE_DENIED"));
    }

    @Test
    void 잘못된_Role_ARN은_AWS를_부르지_않고_400() throws Exception {
        String id = createAccount();
        registerRole(id, "arn:aws:iam::123:role/x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ROLE_ARN"));
        assertThat(aws.assumeCalls).isZero();
    }

    @Test
    void 연결_검증_전에는_사용량을_조회할_수_없다() throws Exception {
        String id = createAccount();
        call(get("/api/v1/aws/accounts/" + id + "/instances"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    void 같은_AWS_계정과_리전을_두_번_연결하지_못한다() throws Exception {
        activeAccount();
        String second = createAccount();
        registerRole(second, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_ALREADY_CONNECTED"));
    }

    @Test
    void 응답과_저장값에_자격증명이_없다() throws Exception {
        String id = activeAccount();
        String response = call(get("/api/v1/aws/accounts/" + id)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContainIgnoringCase("secret").doesNotContain("SessionToken").doesNotContain("ASIA");
    }
}
