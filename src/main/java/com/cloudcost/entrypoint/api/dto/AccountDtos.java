package com.cloudcost.entrypoint.api.dto;

import com.cloudcost.application.AwsAccountService.AccountSetup;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.CustomerRolePolicy;
import com.cloudcost.domain.sync.SyncJob;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AccountDtos {

    private AccountDtos() {
    }

    @Schema(description = "AWS 연결 시작. Access Key는 받지 않는다.")
    public record CreateAccountRequest(
        @Schema(description = "EC2·CloudWatch를 조회할 리전 (기본 ap-northeast-2)", example = "ap-northeast-2")
        String region) {
    }

    @Schema(description = "고객 계정에 만든 Read Only Role의 ARN")
    public record RegisterRoleRequest(
        @NotBlank @Size(max = 2048)
        @Schema(example = "arn:aws:iam::123456789012:role/CloudCostReadOnlyRole", requiredMode = Schema.RequiredMode.REQUIRED)
        String roleArn) {
    }

    public record ErrorInfo(String code, String message) {
    }

    public record AccountResponse(
        UUID accountId,
        @Schema(description = "PENDING: Role 생성 전 / ACTIVE: 실제 AssumeRole 검증 완료 / ERROR: 검증 실패 또는 고쳐야 하는 AWS 오류"
            + "(lastError 참고, 1시간마다 자동 재확인) / DISCONNECTED: 고객이 스택을 삭제함")
        String status,
        String region,
        @Schema(description = "이 연결 전용 External ID. Trust Policy의 sts:ExternalId 조건에 넣는다.")
        String externalId,
        String roleArn,
        String awsAccountId,
        ErrorInfo lastError,
        Instant verifiedAt,
        Instant instancesRefreshedAt,
        Instant usageRefreshedAt,
        @Schema(description = "Role을 만든 고객 계정의 CloudFormation 스택") String stackId,
        Instant disconnectedAt,
        Instant createdAt) {

        public static AccountResponse of(AwsAccount a) {
            return new AccountResponse(
                a.id(),
                a.status().name(),
                a.region().value(),
                a.externalId().value(),
                a.roleArn() == null ? null : a.roleArn().value(),
                a.roleArn() == null ? null : a.roleArn().accountId(),
                a.lastErrorCode() == null ? null : new ErrorInfo(a.lastErrorCode(), a.lastErrorMessage()),
                a.verifiedAt(),
                a.instancesRefreshedAt(),
                a.usageRefreshedAt(),
                a.stackId(),
                a.disconnectedAt(),
                a.createdAt());
        }
    }

    public record RoleSetup(
        @Schema(description = "누르면 값이 모두 채워진 AWS CloudFormation 스택 생성 화면이 열린다. 체크박스 1개 + [스택 생성]이면 연결이 끝난다."
            + " 서버 설정이 없으면 null")
        String quickCreateUrl,
        @Schema(description = "고객 Role에 허용할 액션 (이 2개뿐)") List<String> requiredActions,
        @Schema(description = "템플릿을 직접 올릴 때 넣을 파라미터") Map<String, String> stackParameters,
        @Schema(description = "(예비) Role을 직접 만들 때 신뢰 관계에 붙여 넣을 JSON") Map<String, Object> trustPolicy,
        @Schema(description = "(예비) Role을 직접 만들 때 인라인 권한 정책 JSON") Map<String, Object> permissionPolicy,
        List<String> steps,
        List<String> warnings) {

        static RoleSetup of(AccountSetup setup) {
            List<String> steps = setup.quickCreateUrl() != null
                ? List.of(
                    "1. quickCreateUrl을 열어 연결할 AWS 계정으로 로그인한다.",
                    "2. 맨 아래 'AWS CloudFormation에서 IAM 리소스를 생성할 수 있음을 승인합니다'를 체크하고 [스택 생성]을 누른다.",
                    "3. 1~2분 뒤 GET /api/v1/aws/accounts/{accountId}의 status가 ACTIVE로 바뀐다. 따로 입력할 것은 없다.",
                    "연결을 끊으려면 AWS 콘솔에서 CloudCostInsight-로 시작하는 이 연결의 스택을 삭제한다 (자동으로 DISCONNECTED가 된다).")
                : List.of(
                    "(예비 방법) 1. IAM → 역할 생성 → 사용자 지정 신뢰 정책에 trustPolicy를 붙여 넣는다.",
                    "2. 역할 이름은 CloudCost로 시작하게 정하고 (예: CloudCostReadOnlyRole), 인라인 정책에 permissionPolicy를 붙여 넣는다.",
                    "3. 역할 ARN을 PUT /api/v1/aws/accounts/{accountId}/role 로 등록한다.");
            return new RoleSetup(
                setup.quickCreateUrl(),
                CustomerRolePolicy.ALLOWED_ACTIONS,
                setup.stackParameters(),
                setup.trustPolicy(),
                setup.permissionPolicy(),
                steps,
                setup.warnings());
        }
    }

    public record AccountSetupResponse(AccountResponse account, RoleSetup roleSetup) {

        public static AccountSetupResponse of(AccountSetup setup) {
            return new AccountSetupResponse(AccountResponse.of(setup.account()), RoleSetup.of(setup));
        }
    }

    public record SyncJobResponse(
        UUID jobId,
        String trigger,
        String status,
        boolean instancesRefreshed,
        boolean usageRefreshed,
        @Schema(description = "DescribeInstances 호출 수") int ec2RequestCount,
        @Schema(description = "CloudWatch GetMetricStatistics 호출 수 (고객 계정 무료 한도 사용량)") int cwRequestCount,
        int instanceCount,
        int usageRows,
        ErrorInfo error,
        String warning,
        Instant startedAt,
        Instant finishedAt) {

        public static SyncJobResponse of(SyncJob j) {
            return new SyncJobResponse(
                j.id(), j.trigger().name(), j.status().name(), j.instancesRefreshed(), j.usageRefreshed(),
                j.ec2RequestCount(), j.cwRequestCount(), j.instanceCount(), j.usageRows(),
                j.errorCode() == null ? null : new ErrorInfo(j.errorCode(), j.errorMessage()),
                j.warning(), j.startedAt(), j.finishedAt());
        }
    }
}
