package com.cloudcost.application;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.AccessTokens;
import com.cloudcost.application.port.AccessTokens.IssuedToken;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.application.port.UserRepository;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.domain.user.VerifiedIdentity;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Google 로그인 (ADR-004).
 *
 * <p>브라우저가 Google에서 받은 ID 토큰을 검증하고, 처음이면 가입시킨 뒤 CloudCost Insight 액세스 토큰(JWT)을 발급한다.
 * 이후 API는 이 JWT의 사용자 ID를 owner_id로 쓴다. 비밀번호는 다루지 않는다.
 */
public class AuthService {

    private final GoogleIdentityVerifier google;
    private final AccessTokens tokens;
    private final UserRepository users;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public AuthService(
            GoogleIdentityVerifier google,
            AccessTokens tokens,
            UserRepository users,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.google = google;
        this.tokens = tokens;
        this.users = users;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /** @param newUser 이번 로그인으로 가입했는지 */
    public record LoginResult(IssuedToken token, AppUser user, boolean newUser) {}

    public LoginResult loginWithGoogle(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new AuthenticationFailedException("INVALID_ID_TOKEN", "Google 로그인 정보가 없습니다.");
        }
        VerifiedIdentity identity = google.verify(idToken.trim());
        Instant now = clock.instant();
        Optional<AppUser> existing = users.findByProvider(identity.provider(), identity.subject());
        AppUser user = unitOfWork.inTransaction(() -> users.save(existing
                .map(u -> u.loggedIn(identity, now))
                .orElseGet(() -> AppUser.firstLogin(UUID.randomUUID().toString(), identity, now))));
        return new LoginResult(tokens.issue(user.id(), now), user, existing.isEmpty());
    }

    /** 액세스 토큰 → 사용자 ID. 유효하지 않으면 401. */
    public String authenticate(String accessToken) {
        return tokens.verify(accessToken, clock.instant())
                .orElseThrow(() -> new AuthenticationFailedException(
                        "INVALID_TOKEN", "로그인이 만료되었거나 유효하지 않습니다. 다시 로그인하세요."));
    }

    /** 로컬 DevAuth 사용자는 저장된 프로필이 없을 수 있다. */
    public AppUser me(String ownerId) {
        return users.findByOwnerId(ownerId)
                .orElseGet(() -> new AppUser(ownerId, null, null, null, null, clock.instant(), null));
    }
}
