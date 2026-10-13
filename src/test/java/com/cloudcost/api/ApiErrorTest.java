package com.cloudcost.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.fakes.Fixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApiErrorTest extends IntegrationTestBase {

    @Test
    void 잘못된_형식의_accountId는_400() throws Exception {
        getJson("/api/v1/aws/accounts/not-a-uuid/instances")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 없는_accountId는_404() throws Exception {
        getJson("/api/v1/aws/accounts/" + UUID.randomUUID() + "/costs")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void AWS_권한_부족은_422와_필요한_IAM_액션() throws Exception {
        String id = createAccount();
        aws.describeError = new AwsAccessException(Category.PERMISSION_DENIED, "UnauthorizedOperation",
                "ec2:DescribeInstances", "UnauthorizedOperation: ec2:DescribeInstances 권한이 없습니다. 고객 Role의 권한 정책을 확인하세요.");
        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("AWS_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.error.details.action").value("ec2:DescribeInstances"))
                .andExpect(jsonPath("$.error.message").value(
                        "UnauthorizedOperation: ec2:DescribeInstances 권한이 없습니다. 고객 Role의 권한 정책을 확인하세요."));
    }

    @Test
    void AWS_연결_실패는_502() throws Exception {
        String id = createAccount();
        aws.assumeError = new AwsAccessException(Category.UNAVAILABLE, "ClientError", "sts:AssumeRole",
                "AWS에 연결하지 못했습니다 (sts:AssumeRole).");
        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("AWS_UNAVAILABLE"));
    }

    @Test
    void 서버_AWS_자격증명이_없으면_503으로_사용자_잘못이_아님을_알린다() throws Exception {
        String id = createAccount();
        aws.assumeError = new AwsAccessException(Category.BACKEND_CREDENTIALS_MISSING, "NoCredentials", "sts:AssumeRole",
                "서버의 AWS 자격증명이 설정되지 않았습니다.");
        registerRole(id, Fixtures.CUSTOMER_ROLE_ARN)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("BACKEND_AWS_NOT_CONFIGURED"));
    }

    @Test
    void 잘못된_조회_기간은_400() throws Exception {
        String id = activeAccount();
        getJson("/api/v1/aws/accounts/" + id + "/costs?from=2026-10-08&to=2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PERIOD"));
        getJson("/api/v1/aws/accounts/" + id + "/costs/daily?from=2026-13-01")
                .andExpect(status().isBadRequest());
    }

    @Test
    void Swagger_문서가_열린다() throws Exception {
        getJson("/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/aws/accounts/{accountId}/costs/daily']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.devOwner.name").value("X-Owner-Id"));
    }
}
