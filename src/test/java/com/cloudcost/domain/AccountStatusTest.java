package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.AwsAccount.Status;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.IllegalStatusTransitionException;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.sync.SyncJob;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 연결 상태 전이: PENDING → ACTIVE → ERROR/DISCONNECTED, ERROR → ACTIVE 복구. 허용되지 않은 전이는 거부한다. */
class AccountStatusTest {

    private static final Instant T = Instant.parse("2026-10-08T03:00:00Z");
    private static final RoleArn ROLE = RoleArn.parse("arn:aws:iam::123456789012:role/CloudCostReadOnlyRole");
    private static final RoleArn OTHER = RoleArn.parse("arn:aws:iam::111122223333:role/CloudCostReadOnlyRole");

    private static AwsAccount pending() {
        return AwsAccount.createPending(UUID.randomUUID(), "owner-1", AwsRegion.ofNullable(null), T);
    }

    private static AwsAccount active() {
        AwsAccount account = pending();
        account.attachRole(ROLE, T);
        account.markVerified(T);
        return account;
    }

    @Test
    void PENDING에서는_검증_없이_ACTIVE나_DISCONNECTED가_될_수_없다() {
        AwsAccount account = pending();
        assertThat(account.canCollect()).isFalse();
        assertThatThrownBy(() -> account.markVerified(T)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> account.collectionSucceeded(T)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> account.collectionFailed("UNAVAILABLE", "m", false, T))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> account.markDisconnected(T)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(account.status()).isEqualTo(Status.PENDING);
    }

    @Test
    void 검증에_실패한_적만_있는_ERROR는_수집하지_않고_롤백_삭제로_끊기지_않는다() {
        AwsAccount account = pending();
        account.attachRole(ROLE, T);
        account.verificationFailed("ASSUME_ROLE_DENIED", "AccessDenied: Role을 맡을 수 없습니다.", T);

        assertThat(account.status()).isEqualTo(Status.ERROR);
        assertThat(account.canCollect()).isFalse();
        assertThat(account.canDisconnect()).isFalse();
        assertThat(account.wouldSwitchAwsAccount(OTHER)).as("검증된 적 없으면 다른 계정 Role로 다시 시도할 수 있다").isFalse();

        account.markVerified(T.plusSeconds(60));
        assertThat(account.status()).isEqualTo(Status.ACTIVE);
        assertThat(account.lastErrorCode()).isNull();
    }

    @Test
    void ACTIVE에서_고쳐야_하는_오류는_ERROR_일시_오류는_상태_유지() {
        AwsAccount account = active();
        account.collectionFailed("THROTTLED", "요청 제한", false, T);
        assertThat(account.status()).isEqualTo(Status.ACTIVE);
        assertThat(account.lastErrorCode()).isEqualTo("THROTTLED");

        account.collectionFailed("PERMISSION_DENIED", "AccessDenied: cloudwatch:GetMetricStatistics 권한이 없습니다.", true, T);
        assertThat(account.status()).isEqualTo(Status.ERROR);
        assertThat(account.lastErrorMessage()).contains("권한이 없습니다");
        assertThat(account.canCollect()).as("검증된 뒤 ERROR는 자동 재확인 대상").isTrue();
    }

    @Test
    void ERROR는_이후_수집이_성공하면_ACTIVE로_복구된다() {
        AwsAccount account = active();
        account.collectionFailed("ASSUME_ROLE_DENIED", "m", true, T);

        account.collectionSucceeded(T.plusSeconds(3600));

        assertThat(account.status()).isEqualTo(Status.ACTIVE);
        assertThat(account.lastErrorCode()).isNull();
        assertThat(account.lastErrorMessage()).isNull();
    }

    @Test
    void 스택_삭제는_ACTIVE와_검증된_ERROR에서_DISCONNECTED로_바꾸고_중복_신호는_무시한다() {
        AwsAccount fromActive = active();
        fromActive.markDisconnected(T);
        assertThat(fromActive.status()).isEqualTo(Status.DISCONNECTED);
        fromActive.markDisconnected(T.plusSeconds(10));
        assertThat(fromActive.disconnectedAt()).isEqualTo(T);

        AwsAccount fromError = active();
        fromError.collectionFailed("ASSUME_ROLE_DENIED", "m", true, T);
        fromError.markDisconnected(T);
        assertThat(fromError.status()).isEqualTo(Status.DISCONNECTED);
    }

    @Test
    void DISCONNECTED는_수집하지_않고_실제_검증으로만_다시_ACTIVE가_된다() {
        AwsAccount account = active();
        account.markDisconnected(T);

        assertThat(account.canCollect()).isFalse();
        assertThatThrownBy(() -> account.collectionSucceeded(T)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> account.collectionFailed("ASSUME_ROLE_DENIED", "m", true, T))
                .isInstanceOf(IllegalStatusTransitionException.class);
        assertThat(account.wouldSwitchAwsAccount(OTHER)).as("끊긴 연결에도 다른 계정을 섞지 않는다").isTrue();

        account.markVerified(T.plusSeconds(60));
        assertThat(account.status()).isEqualTo(Status.ACTIVE);
        assertThat(account.disconnectedAt()).isNull();
    }

    @Test
    void sync_job은_일부_실패를_PARTIAL과_한국어_원인으로_남긴다() {
        SyncJob job = SyncJob.start(UUID.randomUUID(), "owner-1", UUID.randomUUID(), SyncJob.Trigger.QUERY, T);
        job.partialFailure("UNAVAILABLE", "AWS 서비스가 일시적으로 응답하지 않습니다.");
        job.partialFailure("THROTTLED", "AWS 요청이 일시적으로 제한되었습니다.");
        job.finish(T.plusSeconds(5));

        assertThat(job.status()).isEqualTo(SyncJob.Status.PARTIAL);
        assertThat(job.errorCode()).isEqualTo("UNAVAILABLE");
        assertThat(job.errorMessage()).contains("일시적으로 응답하지 않습니다");
        assertThat(job.warning()).contains("요청이 일시적으로 제한");

        SyncJob clean = SyncJob.start(UUID.randomUUID(), "owner-1", UUID.randomUUID(), SyncJob.Trigger.QUERY, T);
        clean.finish(T);
        assertThat(clean.status()).isEqualTo(SyncJob.Status.SUCCEEDED);
    }
}
