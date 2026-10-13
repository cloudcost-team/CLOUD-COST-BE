package com.cloudcost.application.port;

import java.time.Instant;
import java.util.Optional;

/** CloudCost Insight가 발급하는 액세스 토큰(JWT). 구현: adapter/auth. */
public interface AccessTokens {

    IssuedToken issue(String userId, Instant now);

    /** @return 서명·발급처·만료가 맞으면 사용자 ID */
    Optional<String> verify(String token, Instant now);

    record IssuedToken(String value, Instant expiresAt) {}
}
