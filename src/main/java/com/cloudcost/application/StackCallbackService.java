package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.application.error.ConflictException;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.StackCallbackResponder;
import com.cloudcost.domain.DomainValidationException;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.account.StackCallback;
import com.cloudcost.domain.account.StackCallback.RequestType;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 고객 스택 신호 처리 (ADR-003).
 *
 * <ul>
 *   <li>Create/Update: 연결 id + External ID가 맞는지 보고, 실제 AssumeRole로 검증한다 → ACTIVE + SUCCESS 응답.
 *       실패하면 FAILED 응답 → 고객 스택이 롤백되어 Role이 지워진다.
 *   <li>Delete: 연결을 DISCONNECTED로 바꾸고 항상 SUCCESS (스택 삭제를 막지 않는다).
 * </ul>
 * 신호 내용은 믿지 않는다. 누구나 토픽에 메시지를 보낼 수 있기 때문이다.
 */
public class StackCallbackService {

    /** 같은 사용자가 이 AWS 계정·리전을 이미 연결해 두었다. */
    public static final String ALREADY_CONNECTED = "ALREADY_CONNECTED";
    /** Role이 스택과 다른 AWS 계정에 있거나, 이 연결이 이미 다른 AWS 계정에 연결되어 있다. */
    public static final String ACCOUNT_MISMATCH = "ACCOUNT_MISMATCH";
    /** 신호에 들어 있는 Role ARN 형식이 올바르지 않다. */
    public static final String INVALID_ROLE_ARN = "INVALID_ROLE_ARN";

    /** 막 만든 Role은 IAM 반영 전이라 잠시 거부될 수 있다. 일시적인 오류만 재시도한다. */
    private static final Set<Category> RETRYABLE =
            Set.of(Category.ASSUME_ROLE_DENIED, Category.PERMISSION_DENIED, Category.THROTTLED, Category.UNAVAILABLE);

    private final AwsAccountRepository accounts;
    private final AwsAccountService accountService;
    private final StackCallbackResponder responder;
    private final Clock clock;
    private final ConnectionSettings settings;
    private final Pauser pauser;

    public StackCallbackService(
            AwsAccountRepository accounts,
            AwsAccountService accountService,
            StackCallbackResponder responder,
            Clock clock,
            ConnectionSettings settings,
            Pauser pauser) {
        this.accounts = accounts;
        this.accountService = accountService;
        this.responder = responder;
        this.clock = clock;
        this.settings = settings;
        this.pauser = pauser;
    }

    public void handle(StackCallback callback) {
        if (callback.requestType() == RequestType.DELETE) {
            handleDelete(callback);
            return;
        }
        String failure = connect(callback);
        responder.respond(callback, failure == null, failure);
    }

    /** @return 실패 사유 (성공하면 null) */
    private String connect(StackCallback callback) {
        Optional<AwsAccount> found = find(callback);
        if (found.isEmpty()) {
            return "CloudCost Insight에서 이 연결을 찾을 수 없습니다. CloudCost Insight에서 연결을 새로 시작하고 새 링크로 스택을 만드세요.";
        }
        AwsAccount account = found.get();
        RoleArn roleArn;
        try {
            roleArn = RoleArn.parse(callback.roleArn());
        } catch (DomainValidationException e) {
            return reject(account, callback, INVALID_ROLE_ARN, e.getMessage());
        }
        if (!roleArn.accountId().equals(callback.stackAccountId())) {
            return reject(account, callback, ACCOUNT_MISMATCH, "Role이 스택과 다른 AWS 계정에 있습니다.");
        }
        if (account.wouldSwitchAwsAccount(roleArn)) {
            return reject(account, callback, ACCOUNT_MISMATCH,
                    "이 연결은 이미 다른 AWS 계정에 연결되어 있습니다. CloudCost Insight에서 연결을 새로 시작하세요.");
        }
        if (accounts.existsActiveByOwnerIdAndAwsAccount(
                account.ownerId(), roleArn.accountId(), account.region().value(), account.id())) {
            return reject(account, callback, ALREADY_CONNECTED, "이 AWS 계정과 리전은 이미 CloudCost Insight에 연결되어 있습니다.");
        }

        account.linkStack(callback.stackId(), clock.instant());
        AwsAccessException last = null;
        for (int attempt = 1; attempt <= settings.verifyAttempts(); attempt++) {
            try {
                // 중간 실패는 기록하지 않는다: 연결 대기 화면이 ERROR를 보고 재시도 중에 실패로 끝내지 않게 한다.
                accountService.verifyRole(account, roleArn, false);
                return null;
            } catch (AwsAccessException e) {
                last = e;
                if (!RETRYABLE.contains(e.category()) || attempt == settings.verifyAttempts()) {
                    break;
                }
                pauser.pause(settings.verifyInterval());
            } catch (ConflictException e) {
                return e.getMessage();
            }
        }
        account.verificationFailed(last.code(), last.getMessage(), clock.instant());
        accounts.save(account);
        return "CloudCost Insight가 Role을 확인하지 못했습니다. " + last.getMessage();
    }

    /**
     * 받아들일 수 없는 신호: 연결에 사유를 남기고(대기 화면이 바로 보여 준다) 같은 사유로 FAILED를 응답한다.
     * 검증된 연결은 {@link AwsAccount#connectionRejected}가 바꾸지 않는다.
     */
    private String reject(AwsAccount account, StackCallback callback, String code, String message) {
        Instant now = clock.instant();
        if (account.verifiedAt() == null) {
            account.linkStack(callback.stackId(), now);
        }
        account.connectionRejected(code, message, now);
        accounts.save(account);
        return message;
    }

    private void handleDelete(StackCallback callback) {
        find(callback).ifPresent(account -> {
            // 생성 실패로 롤백되는 Delete(검증된 적 없음)에서는 상태를 바꾸지 않는다 (실패 원인을 남겨 둔다).
            // 검증된 뒤 ERROR가 된 연결은 스택이 지워졌으므로 DISCONNECTED가 된다.
            if (account.canDisconnect()) {
                account.markDisconnected(clock.instant());
                accounts.save(account);
            }
        });
        responder.respond(callback, true, null);
    }

    private Optional<AwsAccount> find(StackCallback callback) {
        if (callback.externalId() == null || callback.connectionId() == null) {
            return Optional.empty();
        }
        return accounts.findByExternalIdForCallback(callback.externalId())
                .filter(a -> a.id().toString().equals(callback.connectionId()));
    }
}
