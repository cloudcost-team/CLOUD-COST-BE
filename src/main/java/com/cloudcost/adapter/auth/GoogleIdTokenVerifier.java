package com.cloudcost.adapter.auth;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.domain.user.VerifiedIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google ID 토큰 검증. Google 공개키(JWKS, 캐시됨)로 RS256 서명을 확인하고 다음을 검사한다.
 * <ul>
 *   <li>iss = accounts.google.com, aud = 우리 OAuth 클라이언트 ID, exp·iat
 *   <li>email_verified = true (Google이 소유를 확인한 이메일만)
 * </ul>
 * 인가 코드(OAuth 팝업, redirect_uri=postmessage)로 오면 Google 토큰 엔드포인트에서 ID 토큰으로 바꾼 뒤 같은 규칙으로 검증한다.
 * 클라이언트 시크릿은 환경변수(GOOGLE_CLIENT_SECRET)로만 받고 로그·응답에 남기지 않는다.
 * 실패 원인은 응답에 드러내지 않는다 (공격자에게 힌트를 주지 않기 위해).
 */
@Component
class GoogleIdTokenVerifier implements GoogleIdentityVerifier {

    static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    /** initCodeClient의 ux_mode: "popup"이 쓰는 고정 redirect_uri */
    static final String POPUP_REDIRECT_URI = "postmessage";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;
    private final String clientSecret;
    private final URI tokenEndpoint;
    private final DefaultJWTProcessor<SecurityContext> processor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Autowired
    GoogleIdTokenVerifier(
            @Value("${cci.auth.google-client-id:}") String clientId,
            @Value("${cci.auth.google-client-secret:}") String clientSecret) throws MalformedURLException {
        this(clientId, clientSecret, URI.create(GOOGLE_TOKEN_ENDPOINT),
                JWKSourceBuilder.create(URI.create(GOOGLE_JWKS).toURL()).build());
    }

    /** 테스트용: 공개키 출처를 바꿔 끼운다 (인가 코드 방식은 설정 안 됨). */
    GoogleIdTokenVerifier(String clientId, JWKSource<SecurityContext> keys) {
        this(clientId, "", URI.create(GOOGLE_TOKEN_ENDPOINT), keys);
    }

    /** 테스트용: 토큰 엔드포인트와 공개키 출처를 바꿔 끼운다. */
    GoogleIdTokenVerifier(String clientId, String clientSecret, URI tokenEndpoint, JWKSource<SecurityContext> keys) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.tokenEndpoint = tokenEndpoint;
        this.processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                this.clientId, null, Set.of("sub", "iss", "aud", "exp", "iat")));
    }

    @Override
    public VerifiedIdentity verify(String idToken) {
        if (clientId.isEmpty()) {
            throw notConfigured("GOOGLE_CLIENT_ID");
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
                    AppUser.GOOGLE, claims.getSubject(), claims.getStringClaim("email"), claims.getStringClaim("name"),
                    claims.getStringClaim("picture"));
        } catch (java.text.ParseException e) {
            throw invalid();
        }
    }

    @Override
    public VerifiedIdentity verifyAuthCode(String code) {
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            throw notConfigured(clientId.isEmpty() ? "GOOGLE_CLIENT_ID" : "GOOGLE_CLIENT_SECRET");
        }
        String idToken = exchange(code);
        try {
            return verify(idToken);
        } catch (AuthenticationFailedException e) {
            throw invalidCode();
        }
    }

    /** 인가 코드 → ID 토큰. Google 응답 본문(오류 설명 포함)은 응답·로그에 남기지 않는다. */
    private String exchange(String code) {
        String form = form(Map.of(
                "code", code,
                "client_id", clientId,
                "client_secret", clientSecret,
                "redirect_uri", POPUP_REDIRECT_URI,
                "grant_type", "authorization_code"));
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(tokenEndpoint)
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw invalidCode();
            }
            JsonNode idToken = mapper.readTree(response.body()).path("id_token");
            if (!idToken.isTextual() || idToken.asText().isBlank()) {
                throw invalidCode();
            }
            return idToken.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw invalidCode();
        } catch (IOException e) {
            throw invalidCode();
        }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(f -> f.getKey() + "=" + URLEncoder.encode(f.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static AuthenticationFailedException notConfigured(String envName) {
        return new AuthenticationFailedException(
                "GOOGLE_LOGIN_NOT_CONFIGURED", "서버에 Google 로그인이 설정되지 않았습니다 (관리자 설정 필요: " + envName + ").");
    }

    private static AuthenticationFailedException invalidCode() {
        return new AuthenticationFailedException("INVALID_AUTH_CODE", "Google 로그인을 마치지 못했습니다. 다시 로그인하세요.");
    }

    private static AuthenticationFailedException invalid() {
        return new AuthenticationFailedException("INVALID_ID_TOKEN", "Google 로그인 정보를 확인하지 못했습니다. 다시 로그인하세요.");
    }
}
