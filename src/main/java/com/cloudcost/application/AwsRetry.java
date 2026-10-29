package com.cloudcost.application;

import com.cloudcost.application.error.AwsAccessException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 고객 계정 호출 재시도. 요청 제한(Throttling·RequestLimitExceeded)과 일시 장애만 지수 백오프로 다시 시도한다.
 * 권한 오류 등 고쳐야 하는 오류는 바로 던진다. SDK 자체 재시도는 꺼 두었으므로(호출 수를 정확히 세기 위해) 여기가 유일한 재시도다.
 */
final class AwsRetry {

    static final int MAX_ATTEMPTS = 3;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

    private final Pauser pauser;

    AwsRetry(Pauser pauser) {
        this.pauser = pauser;
    }

    <T> T call(Supplier<T> call) {
        return call(call, () -> { }, () -> true);
    }

    /**
     * @param onAttempt 실제 요청을 보낼 때마다 (성공·실패 무관) 호출 — 요청 수 집계용
     * @param mayRetry  다시 보내도 되는지 (CloudWatch 호출 상한 확인용)
     */
    <T> T call(Supplier<T> call, Runnable onAttempt, BooleanSupplier mayRetry) {
        Duration backoff = FIRST_BACKOFF;
        for (int attempt = 1; ; attempt++) {
            onAttempt.run();
            try {
                return call.get();
            } catch (AwsAccessException e) {
                if (!e.retryable() || attempt >= MAX_ATTEMPTS || !mayRetry.getAsBoolean()) {
                    throw e;
                }
            }
            pauser.pause(backoff);
            backoff = backoff.multipliedBy(2);
        }
    }
}
