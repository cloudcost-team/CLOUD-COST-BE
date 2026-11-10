package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ConnectionAlias;
import com.cloudcost.domain.account.RoleArn;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 연결 목록 표시용 규칙: 연결 이름, 한국어 리전 이름, 오래된 PENDING, 삭제 후 스택 안내 (이슈 #15) */
class ConnectionDisplayTest {

    private static final Instant T = Instant.parse("2026-10-08T03:00:00Z");
    private static final RoleArn ROLE = RoleArn.parse("arn:aws:iam::123456789012:role/CloudCostReadOnlyRole");

    private static AwsAccount pending() {
        return AwsAccount.createPending(UUID.randomUUID(), "owner-1", AwsRegion.ofNullable(null), T);
    }

    @Test
    void 이름은_앞뒤_공백을_지우고_비면_null() {
        assertThat(ConnectionAlias.normalize("  개인 계정 ")).isEqualTo("개인 계정");
        assertThat(ConnectionAlias.normalize(" \t ")).isNull();
        assertThat(ConnectionAlias.normalize(null)).isNull();
    }

    @Test
    void 이름은_글자_수로_40자까지() {
        assertThat(ConnectionAlias.normalize("한".repeat(40))).hasSize(40);
        assertThat(ConnectionAlias.normalize("😀".repeat(40))).as("이모지도 한 글자로 센다").isNotNull();
        assertThatThrownBy(() -> ConnectionAlias.normalize("한".repeat(41)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("40자");
        assertThatThrownBy(() -> ConnectionAlias.normalize("a\u0000b")).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "ap-northeast-2, 서울",
        "ap-northeast-1, 도쿄",
        "us-east-1, 버지니아 북부",
        "us-west-2, 오레곤",
        "eu-west-1, 아일랜드",
        "ap-east-1, ap-east-1"
    })
    void 리전은_한국어_이름으로_보여_주고_모르면_코드_그대로(String code, String label) {
        assertThat(new AwsRegion(code).koreanLabel()).isEqualTo(label);
    }

    @Test
    void PENDING은_1시간이_지나면_버려진_연결로_본다() {
        AwsAccount account = pending();
        assertThat(account.isAbandonedPending(T.plus(Duration.ofMinutes(59)))).isFalse();
        assertThat(account.isAbandonedPending(T.plus(Duration.ofHours(1)))).isTrue();

        account.attachRole(ROLE, T);
        account.markVerified(T);
        assertThat(account.isAbandonedPending(T.plus(Duration.ofDays(30)))).as("ACTIVE는 숨기지 않는다").isFalse();
    }

    @Test
    void 검증된_연결만_삭제_후_스택_정리를_안내한다() {
        AwsAccount account = pending();
        assertThat(account.mayStillHaveCustomerRole()).isFalse();
        account.attachRole(ROLE, T);
        account.verificationFailed("AccessDenied", "실패", T);
        assertThat(account.mayStillHaveCustomerRole()).as("검증 실패(롤백)").isFalse();

        account.markVerified(T);
        assertThat(account.mayStillHaveCustomerRole()).isTrue();
        account.collectionFailed("AccessDenied", "권한 없음", true, T);
        assertThat(account.mayStillHaveCustomerRole()).as("검증된 뒤 ERROR").isTrue();
        account.markDisconnected(T);
        assertThat(account.mayStillHaveCustomerRole()).as("스택이 이미 삭제됨").isFalse();
    }
}
