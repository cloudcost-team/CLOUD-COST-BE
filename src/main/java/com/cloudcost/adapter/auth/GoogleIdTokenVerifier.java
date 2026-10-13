package com.cloudcost.adapter.auth;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.domain.user.VerifiedIdentity;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google ID 토큰 검증. Google 공개키(JWKS, 캐시됨)로 RS256 서명을 확인하고 다음을 검사한다.
 * <ul>
 *   <li>iss = accounts.google.com, aud = 우리 OAuth 클라이언트 ID, exp·iat
 *   <li>email_verified = true (Google이 소유를 확인한 이메일만)
 * </ul>
 * 실패 원인은 응답에 드러내지 않는다 (공격자에게 힌트를 주지 않기 위해).
 */
@Component
class GoogleIdTokenVerifier implements GoogleIdentityVerifier {

    static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private final DefaultJWTProcessor<SecurityContext> processor;

    @Autowired
    GoogleIdTokenVerifier(@Value("${cci.auth.google-client-id:}") String clientId) throws MalformedURLException {
        this(clientId, JWKSourceBuilder.create(URI.create(GOOGLE_JWKS).toURL()).build());
    }

    /** 테스트용: 공개키 출처를 바꿔 끼운다. */
    GoogleIdTokenVerifier(String clientId, JWKSource<SecurityContext> keys) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                this.clientId, null, Set.of("sub", "iss", "aud", "exp", "iat")));
    }

    @Override
    public VerifiedIdentity verify(String idToken) {
        if (clientId.isEmpty()) {
            throw new AuthenticationFailedException(
                    "GOOGLE_LOGIN_NOT_CONFIGURED", "서버에 Google 로그인이 설정되지 않았습니다 (관리자 설정 필요: GOOGLE_CLIENT_ID).");
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(idToken, null);
        } catch (Exception e) {
            throw invalid();
        }
        try {
            if (!ISSUERS.contains(claims.getIssuer()) || !Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))) {
                throw invalid();
            }
            return new VerifiedIdentity(
                    AppUser.GOOGLE, claims.getSubject(), claims.getStringClaim("email"), claims.getStringClaim("name"));
        } catch (java.text.ParseException e) {
            throw invalid();
        }
    }

    private static AuthenticationFailedException invalid() {
        return new AuthenticationFailedException("INVALID_ID_TOKEN", "Google 로그인 정보를 확인하지 못했습니다. 다시 로그인하세요.");
    }
}
