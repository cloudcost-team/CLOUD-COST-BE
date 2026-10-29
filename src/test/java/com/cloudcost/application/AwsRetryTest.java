package com.cloudcost.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import com.cloudcost.fakes.RecordingPauser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AwsRetryTest {

    private final RecordingPauser pauser = new RecordingPauser();
    private final AwsRetry retry = new AwsRetry(pauser);
    private int attempts;

    private static AwsAccessException error(Category category) {
        return new AwsAccessException(category, "Code", "cloudwatch:GetMetricStatistics", "메시지");
    }

    private String failTimes(int failures, Category category) {
        attempts++;
        if (attempts <= failures) {
            throw error(category);
        }
        return "ok";
    }

    @Test
    void 요청_제한은_지수_백오프로_다시_시도한다() {
        String result = retry.call(() -> failTimes(2, Category.THROTTLED));

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).isEqualTo(3);
        assertThat(pauser.pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void 최대_횟수를_넘으면_마지막_오류를_던진다() {
        assertThatThrownBy(() -> retry.call(() -> failTimes(10, Category.UNAVAILABLE)))
                .isInstanceOfSatisfying(AwsAccessException.class,
                        e -> assertThat(e.category()).isEqualTo(Category.UNAVAILABLE));
        assertThat(attempts).isEqualTo(AwsRetry.MAX_ATTEMPTS);
    }

    @Test
    void 고쳐야_하는_오류는_다시_시도하지_않는다() {
        for (Category category : new Category[] {
                Category.ASSUME_ROLE_DENIED, Category.PERMISSION_DENIED, Category.REGION_DISABLED,
                Category.BACKEND_CREDENTIALS_MISSING, Category.UNKNOWN}) {
            attempts = 0;
            assertThatThrownBy(() -> retry.call(() -> failTimes(1, category))).isInstanceOf(AwsAccessException.class);
            assertThat(attempts).as(category.name()).isEqualTo(1);
        }
        assertThat(pauser.pauses).isEmpty();
    }

    @Test
    void 호출_상한에_닿으면_다시_보내지_않고_시도마다_요청_수를_센다() {
        int[] counted = {0};
        assertThatThrownBy(() -> retry.call(
                () -> failTimes(10, Category.THROTTLED), () -> counted[0]++, () -> counted[0] < 2))
                .isInstanceOf(AwsAccessException.class);
        assertThat(attempts).isEqualTo(2);
        assertThat(counted[0]).isEqualTo(2);
    }

    @Test
    void 연결을_끊는_오류와_재시도_가능한_오류_분류() {
        assertThat(error(Category.ASSUME_ROLE_DENIED).breaksConnection()).isTrue();
        assertThat(error(Category.PERMISSION_DENIED).breaksConnection()).isTrue();
        assertThat(error(Category.REGION_DISABLED).breaksConnection()).isTrue();
        assertThat(error(Category.THROTTLED).breaksConnection()).isFalse();
        assertThat(error(Category.UNAVAILABLE).breaksConnection()).isFalse();
        assertThat(error(Category.BACKEND_CREDENTIALS_MISSING).breaksConnection())
                .as("우리 서버 설정 문제로 고객 연결을 ERROR로 만들지 않는다").isFalse();
        assertThat(error(Category.THROTTLED).retryable()).isTrue();
        assertThat(error(Category.UNAVAILABLE).retryable()).isTrue();
        assertThat(error(Category.PERMISSION_DENIED).retryable()).isFalse();
    }
}
