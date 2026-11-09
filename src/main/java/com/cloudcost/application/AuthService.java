package com.cloudcost.application;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.AccessTokens;
import com.cloudcost.application.port.AccessTokens.IssuedToken;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.application.port.RefreshTokenRepository;
import com.cloudcost.application.port.RefreshTokenRepository.StoredRefreshToken;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.application.port.UserRepository;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.domain.user.VerifiedIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Google 로그인 (ADR-004).
 *
 * <p>브라우저가 Google에서 받은 ID 토큰을 검증하고, 처음이면 가입시킨 뒤 CloudCost Insight 액세스 토큰(JWT)을 발급한다.
 * 이후 API는 이 JWT의 사용자 ID를 owner_id로 쓴다. 비밀번호는 다루지 않는다.
 *
 * <p>리프레시 토큰 (ADR-017): 로그인할 때 함께 발급하고, 쓸 때마다 새 토큰으로 바꾼다(rotation).
 * 이미 쓴 토큰이 다시 오면 탈취로 보고 같은 로그인(family)의 토큰을 모두 무효화한다.
 */
public class AuthService {

    private final GoogleIdentityVerifier google;
    private final AccessTokens tokens;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final Duration refreshTtl;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
            GoogleIdentityVerifier google,
            AccessTokens tokens,
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            Duration refreshTtl,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.google = google;
        this.tokens = tokens;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.refreshTtl = refreshTtl;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    /**
     * @param refreshToken 원문. HttpOnly 쿠키로만 내려보낸다
     * @param newUser 이번 로그인으로 가입했는지
     */
    public record LoginResult(IssuedToken token, IssuedToken refreshToken, AppUser user, boolean newUser) {}

    /** @param refreshToken 새로 바뀐 리프레시 토큰 원문 */
    public record RefreshResult(IssuedToken token, IssuedToken refreshToken) {}

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
        IssuedToken refresh = unitOfWork.inTransaction(() -> {
            refreshTokens.deleteExpired(user.id(), now);
            return issueRefreshToken(user.id(), UUID.randomUUID(), now);
        });
        return new LoginResult(tokens.issue(user.id(), now), refresh, user, existing.isEmpty());
    }

    /** 리프레시 토큰 → 새 액세스 토큰 + 새 리프레시 토큰. 쓴 토큰은 다시 쓸 수 없다. */
    public RefreshResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw invalidRefreshToken();
        }
        Instant now = clock.instant();
        StoredRefreshToken stored = refreshTokens.findByHash(hash(rawRefreshToken.trim()))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (stored.revokedAt() != null || !now.isBefore(stored.expiresAt())) {
            throw invalidRefreshToken();
        }
        Optional<IssuedToken> next = stored.usedAt() != null ? Optional.empty() : unitOfWork.inTransaction(() ->
                refreshTokens.markUsed(stored.id(), now)
                        ? Optional.of(issueRefreshToken(stored.ownerId(), stored.familyId(), now))
                        : Optional.empty());
        if (next.isEmpty()) {
            refreshTokens.revokeFamily(stored.familyId(), now);
            throw new AuthenticationFailedException(
                    "REFRESH_TOKEN_REUSED", "이미 사용된 로그인 정보입니다. 보안을 위해 다시 로그인하세요.");
        }
        return new RefreshResult(tokens.issue(stored.ownerId(), now), next.get());
    }

    /** 이 로그인(family)의 리프레시 토큰을 모두 무효화한다. 토큰이 없거나 모르는 값이면 아무것도 하지 않는다. */
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByHash(hash(rawRefreshToken.trim()))
                .ifPresent(t -> refreshTokens.revokeFamily(t.familyId(), clock.instant()));
    }

    private IssuedToken issueRefreshToken(String ownerId, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(refreshTtl);
        refreshTokens.save(new StoredRefreshToken(
                UUID.randomUUID(), ownerId, familyId, hash(raw), now, expiresAt, null, null));
        return new IssuedToken(raw, expiresAt);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AuthenticationFailedException invalidRefreshToken() {
        return new AuthenticationFailedException(
                "INVALID_REFRESH_TOKEN", "로그인이 만료되었거나 유효하지 않습니다. 다시 로그인하세요.");
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
