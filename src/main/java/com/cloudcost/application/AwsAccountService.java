package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.ConflictException;
import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.CustomerAwsGateway;
import com.cloudcost.application.port.CustomerAwsSession;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.application.port.SyncJobRepository;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.application.port.UserRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ConnectionAlias;
import com.cloudcost.domain.account.CustomerRolePolicy;
import com.cloudcost.domain.account.QuickCreateLink;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.sync.SyncJob;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AWS 연결.
 * <ol>
 *   <li>create: External ID를 발급하고 CloudFormation 빠른 생성 링크를 돌려준다 (PENDING).
 *   <li>고객이 링크로 스택을 만들면 SNS 신호가 오고 {@link StackCallbackService}가 {@link #verifyRole}로 검증한다.
 *   <li>예비 경로 registerRole: Role ARN을 직접 받아 같은 방식으로 검증한다.
 * </ol>
 * 연결 삭제(delete)는 우리 DB의 연결과 수집 기록만 지운다. 고객 계정의 스택(Role)은 READ ONLY라 지우지 못하므로 안내만 한다.
 * Mock으로 성공을 만들지 않는다. 실제 AssumeRole + DescribeInstances 1건이 성공해야 ACTIVE가 된다.
 */
public class AwsAccountService {

    private final AwsAccountRepository accounts;
    private final UserRepository users;
    private final Ec2InstanceRepository instances;
    private final Ec2UsageRepository usage;
    private final SyncJobRepository syncJobs;
    private final CustomerAwsGateway gateway;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final ConnectionSettings settings;

    public AwsAccountService(
        AwsAccountRepository accounts,
        UserRepository users,
        Ec2InstanceRepository instances,
        Ec2UsageRepository usage,
        SyncJobRepository syncJobs,
        CustomerAwsGateway gateway,
        UnitOfWork unitOfWork,
        Clock clock,
        ConnectionSettings settings) {
        this.accounts = accounts;
        this.users = users;
        this.instances = instances;
        this.usage = usage;
        this.syncJobs = syncJobs;
        this.gateway = gateway;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * @param quickCreateUrl   누르면 값이 채워진 CloudFormation 스택 생성 화면이 열리는 링크 (설정이 없으면 null)
     * @param stackParameters  템플릿을 직접 올릴 때 넣을 파라미터
     * @param trustPolicy      (예비) Role을 직접 만들 때 "신뢰 관계"에 붙여 넣을 JSON
     * @param permissionPolicy (예비) Role을 직접 만들 때 인라인 권한 정책 JSON
     */
    public record AccountSetup(
        AwsAccount account,
        String quickCreateUrl,
        Map<String, String> stackParameters,
        Map<String, Object> trustPolicy,
        Map<String, Object> permissionPolicy,
        List<String> warnings) {
    }

    /**
     * @param stackName      고객 계정에 남아 있을 수 있는 스택 이름 (CloudCostInsight-연결 id 앞 8자리)
     * @param awsCleanupNeeded true면 고객이 AWS 콘솔에서 스택을 직접 삭제해야 접근 권한이 완전히 사라진다
     * @param message        사용자에게 보여 줄 한국어 안내
     */
    public record Deletion(
        UUID accountId,
        AwsAccount.Status previousStatus,
        String stackName,
        boolean awsCleanupNeeded,
        String message) {
    }

    public AccountSetup create(String ownerId, String region) {
        return create(ownerId, region, null);
    }

    public AccountSetup create(String ownerId, String region, String alias) {
        AwsRegion awsRegion = AwsRegion.ofNullable(region);
        String name = ConnectionAlias.normalize(alias);
        Instant now = clock.instant();
        AwsAccount account = unitOfWork.inTransaction(() -> {
            users.ensureExists(ownerId, now);
            AwsAccount pending = AwsAccount.createPending(UUID.randomUUID(), ownerId, awsRegion, now);
            pending.rename(name, now);
            return accounts.add(pending);
        });
        return setupOf(account);
    }

    /** 연결 이름 변경. 비우면 이름을 지운다. */
    public AwsAccount rename(String ownerId, UUID accountId, String alias) {
        AwsAccount account = get(ownerId, accountId);
        account.rename(alias, clock.instant());
        return accounts.save(account);
    }

    /**
     * 연결과 이 연결로 모은 데이터(인스턴스·시간별 사용량·수집 기록)를 모두 지운다. 남의 연결이면 404.
     * 고객 계정의 스택(Role)은 건드리지 않는다. 이후 그 스택에서 오는 신호는 연결을 찾지 못해 무시된다
     * ({@link StackCallbackService}).
     */
    public Deletion delete(String ownerId, UUID accountId) {
        AwsAccount account = get(ownerId, accountId);
        unitOfWork.run(() -> {
            usage.deleteUsageByOwnerIdAndAccountId(ownerId, accountId);
            instances.deleteInstancesByOwnerIdAndAccountId(ownerId, accountId);
            syncJobs.deleteAllByOwnerIdAndAccountId(ownerId, accountId);
            if (!accounts.deleteByOwnerIdAndId(ownerId, accountId)) {
                throw notFound();
            }
        });
        String stackName = QuickCreateLink.stackName(account);
        boolean cleanup = account.mayStillHaveCustomerRole();
        String message;
        if (cleanup) {
            // 우리는 읽기 전용이라 고객 스택을 지울 수 없다. 짧게 다음 할 일만 알려 준다.
            message = "삭제했습니다. AWS 쪽도 끊으려면 AWS 콘솔 → CloudFormation에서 '" + stackName + "' 스택을 삭제하세요.";
        } else if (account.status() == AwsAccount.Status.PENDING) {
            message = "연결을 취소했습니다.";
        } else {
            message = "삭제했습니다.";
        }
        return new Deletion(accountId, account.status(), stackName, cleanup, message);
    }

    public AccountSetup setup(String ownerId, UUID accountId) {
        return setupOf(get(ownerId, accountId));
    }

    public AwsAccount registerRole(String ownerId, UUID accountId, String roleArnValue) {
        AwsAccount account = get(ownerId, accountId);
        RoleArn roleArn = RoleArn.parse(roleArnValue);
        if (account.wouldSwitchAwsAccount(roleArn)) {
            throw new ConflictException(
                "ROLE_ACCOUNT_CHANGED",
                "이 연결은 이미 다른 AWS 계정(" + RoleArn.maskAccountId(account.roleArn().accountId())
                    + ")으로 연결되어 있습니다. 다른 계정은 새 연결을 만들어 등록하세요.");
        }
        if (accounts.existsActiveByOwnerIdAndAwsAccount(
            ownerId, roleArn.accountId(), account.region().value(), account.id())) {
            throw new ConflictException(
                "ACCOUNT_ALREADY_CONNECTED",
                "같은 AWS 계정(" + RoleArn.maskAccountId(roleArn.accountId()) + ")과 리전이 이미 연결되어 있습니다.");
        }

        return verifyRole(account, roleArn);
    }

    /**
     * 실제 AssumeRole(이 연결의 External ID) + DescribeInstances 1건. 성공하면 ACTIVE로 저장한다.
     * 실패하면 원인을 연결에 기록하고 AwsAccessException을 그대로 던진다.
     */
    AwsAccount verifyRole(AwsAccount account, RoleArn roleArn) {
        return verifyRole(account, roleArn, true);
    }

    /**
     * @param recordFailure false면 실패해도 연결에 기록하지 않는다. 재시도 중간의 실패(막 만든 Role의 IAM 반영 지연)로
     *                      연결이 ERROR가 되어 화면이 실패로 보이지 않게, 마지막 시도 결과만 남길 때 쓴다.
     */
    AwsAccount verifyRole(AwsAccount account, RoleArn roleArn, boolean recordFailure) {
        account.attachRole(roleArn, clock.instant());
        try (CustomerAwsSession session =
                 gateway.open(roleArn, account.externalId(), account.region(), sessionName(account))) {
            session.verifyAccess();
        } catch (AwsAccessException e) {
            if (recordFailure) {
                account.verificationFailed(e.code(), e.getMessage(), clock.instant());
                accounts.save(account);
            }
            throw e;
        }
        account.markVerified(clock.instant());
        return accounts.save(account);
    }

    public AwsAccount get(String ownerId, UUID accountId) {
        return accounts.findByOwnerIdAndId(ownerId, accountId).orElseThrow(AwsAccountService::notFound);
    }

    /**
     * 사용량 조회가 가능한 연결: ACTIVE, 또는 한 번 검증된 뒤 오류가 난 ERROR (저장된 데이터 + 오류 원인을 보여 주고 자동 재확인한다).
     */
    public AwsAccount getActive(String ownerId, UUID accountId) {
        AwsAccount account = get(ownerId, accountId);
        if (account.status() == AwsAccount.Status.DISCONNECTED) {
            throw new ConflictException(
                "ACCOUNT_DISCONNECTED",
                "AWS에서 CloudCost Insight 스택(Role)이 삭제되어 연결이 해제되었습니다. 저장된 기록은 남아 있지만 새로 수집하지 않습니다. "
                    + "다시 연결하려면 연결을 새로 시작하세요.");
        }
        if (!account.canCollect()) {
            throw new ConflictException(
                "ACCOUNT_NOT_ACTIVE",
                "연결이 아직 완료되지 않았습니다. 연결 시작 응답의 quickCreateUrl로 AWS에서 스택을 만들거나, "
                    + "PUT /api/v1/aws/accounts/" + accountId + "/role 로 Role ARN을 등록하세요.");
        }
        return account;
    }

    public List<AwsAccount> list(String ownerId) {
        return list(ownerId, true);
    }

    /**
     * @param includePending false면 시작만 하고 1시간 넘게 PENDING인 연결({@link AwsAccount#isAbandonedPending})을 뺀다
     */
    public List<AwsAccount> list(String ownerId, boolean includePending) {
        List<AwsAccount> all = accounts.findAllByOwnerId(ownerId);
        if (includePending) {
            return all;
        }
        Instant now = clock.instant();
        return all.stream().filter(a -> !a.isAbandonedPending(now)).toList();
    }

    public List<SyncJob> recentSyncJobs(String ownerId, UUID accountId, int limit) {
        get(ownerId, accountId);
        return syncJobs.findRecentByOwnerIdAndAccountId(ownerId, accountId, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * 고객 CloudTrail에서 우리 호출을 추적할 수 있도록 세션 이름에 연결 id를 넣는다.
     */
    static String sessionName(AwsAccount account) {
        return "cci-" + account.id();
    }

    static NotFoundException notFound() {
        return new NotFoundException("ACCOUNT_NOT_FOUND", "AWS 연결을 찾을 수 없습니다.");
    }

    private AccountSetup setupOf(AwsAccount account) {
        List<String> warnings = new ArrayList<>();
        String principal = settings.backendRoleArn();
        if (principal.isEmpty()) {
            principal = "<서버 설정 CCI_BACKEND_ROLE_ARN 값>";
            warnings.add("서버에 CCI_BACKEND_ROLE_ARN이 설정되지 않았습니다 (관리자 설정 필요).");
        }
        String topicRegion = QuickCreateLink.topicRegion(settings.callbackTopicArn());
        Map<String, String> parameters =
            QuickCreateLink.parameters(account, principal, settings.callbackTopicArn());
        String link = null;
        if (settings.templateUrl().isEmpty() || topicRegion == null) {
            warnings.add("서버에 CCI_TEMPLATE_URL 또는 CCI_CALLBACK_TOPIC_ARN이 설정되지 않아 빠른 생성 링크를 만들지 못했습니다 "
                + "(관리자 설정 필요). 그동안은 trustPolicy·permissionPolicy로 Role을 직접 만들고 PUT .../role로 등록하세요.");
        } else if (!settings.backendRoleArn().isEmpty()) {
            link = QuickCreateLink.build(settings.templateUrl(), topicRegion, QuickCreateLink.stackName(account), parameters);
        }
        return new AccountSetup(
            account,
            link,
            parameters,
            CustomerRolePolicy.trustPolicy(principal, account.externalId()),
            CustomerRolePolicy.permissionPolicy(account.region()),
            warnings);
    }
}
