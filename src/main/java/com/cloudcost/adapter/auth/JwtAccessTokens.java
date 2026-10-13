package com.cloudcost.adapter.auth;

import com.cloudcost.application.port.AccessTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CloudCost Insight 액세스 토큰: HS256 JWT, sub = 사용자 ID, 기본 1시간.
 * 서명 키는 환경변수 JWT_SECRET(32바이트 이상). 비어 있으면 실행할 때마다 임의 키를 만든다(로컬 전용, 재시작하면 다시 로그인).
 */
@Component
class JwtAccessTokens implements AccessTokens {

    private static final Logger log = LoggerFactory.getLogger(JwtAccessTokens.class);
    static final String ISSUER = "cloudcost-insight";
    static final String AUDIENCE = "cloudcost-insight-api";

    private final byte[] secret;
    private final Duration ttl;

    JwtAccessTokens(
            @Value("${cci.auth.jwt-secret:}") String secret,
            @Value("${cci.auth.token-ttl:1h}") Duration ttl) {
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("JWT_SECRET이 없어 임시 서명 키를 사용합니다. 서버를 재시작하면 다시 로그인해야 합니다 (로컬 전용).");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
            if (this.secret.length < 32) {
                throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
            }
        }
        this.ttl = ttl;
    }

    @Override
    public IssuedToken issue(String userId, Instant now) {
        Instant expiresAt = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .subject(userId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return new IssuedToken(jwt.serialize(), expiresAt);
        } catch (JOSEException e) {
            throw new IllegalStateException("토큰을 만들지 못했습니다.", e);
        }
    }

    @Override
    public Optional<String> verify(String token, Instant now) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            boolean valid = ISSUER.equals(claims.getIssuer())
                    && claims.getAudience() != null && claims.getAudience().equals(List.of(AUDIENCE))
                    && exp != null && now.isBefore(exp.toInstant())
                    && claims.getSubject() != null && !claims.getSubject().isBlank();
            return valid ? Optional.of(claims.getSubject()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
