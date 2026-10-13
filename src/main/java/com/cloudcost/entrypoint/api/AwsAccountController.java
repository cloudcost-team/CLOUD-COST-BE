package com.cloudcost.entrypoint.api;

import com.cloudcost.application.AwsAccountService;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.api.dto.AccountDtos.AccountResponse;
import com.cloudcost.entrypoint.api.dto.AccountDtos.AccountSetupResponse;
import com.cloudcost.entrypoint.api.dto.AccountDtos.CreateAccountRequest;
import com.cloudcost.entrypoint.api.dto.AccountDtos.RegisterRoleRequest;
import com.cloudcost.entrypoint.api.dto.AccountDtos.SyncJobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/aws/accounts")
@Tag(name = "1. AWS 연결", description = "External ID 발급 → Role ARN 등록(실제 AssumeRole 검증). Access Key는 받지 않는다.")
class AwsAccountController {

    private final AwsAccountService accounts;

    AwsAccountController(AwsAccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "① 연결 시작: External ID와 IAM 정책 발급",
        description = "서버가 SecureRandom으로 이 연결 전용 External ID를 만들고, 고객 계정 IAM에 그대로 붙여 넣을 "
            + "Trust Policy와 권한 정책(ec2:DescribeInstances, cloudwatch:GetMetricStatistics)을 돌려준다. 상태는 PENDING.")
    AccountSetupResponse create(@CurrentOwner String owner,
                                @RequestBody(required = false) CreateAccountRequest request) {
        return AccountSetupResponse.of(accounts.create(owner, request == null ? null : request.region()));
    }

    @PutMapping("/{accountId}/role")
    @Operation(
        summary = "② Role ARN 등록 + 실제 연결 검증",
        description = "STS AssumeRole(External ID 포함)과 DescribeInstances 1건을 실제로 호출해 성공하면 ACTIVE가 된다. "
            + "실패하면 원인을 한국어로 알려 준다 (422: Role/권한 문제, 503: 서버 AWS 설정 문제, 502: AWS 연결 실패).")
    AccountResponse registerRole(
        @CurrentOwner String owner, @PathVariable UUID accountId, @Valid @RequestBody RegisterRoleRequest request) {
        return AccountResponse.of(accounts.registerRole(owner, accountId, request.roleArn()));
    }

    @GetMapping
    @Operation(summary = "내 AWS 연결 목록")
    List<AccountResponse> list(@CurrentOwner String owner) {
        return accounts.list(owner).stream().map(AccountResponse::of).toList();
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "연결 상세 + Role 설정 안내 (External ID, 정책 JSON)")
    AccountSetupResponse get(@CurrentOwner String owner, @PathVariable UUID accountId) {
        return AccountSetupResponse.of(accounts.setup(owner, accountId));
    }

    @GetMapping("/{accountId}/sync-jobs")
    @Operation(
        summary = "최근 AWS 수집 기록",
        description = "조회 시점에 캐시가 오래되어 AWS를 부를 때마다 1건 생긴다. cwRequestCount로 CloudWatch 호출 수를 확인할 수 있다.")
    List<SyncJobResponse> syncJobs(
        @CurrentOwner String owner, @PathVariable UUID accountId, @RequestParam(defaultValue = "20") int limit) {
        return accounts.recentSyncJobs(owner, accountId, limit).stream().map(SyncJobResponse::of).toList();
    }
}
