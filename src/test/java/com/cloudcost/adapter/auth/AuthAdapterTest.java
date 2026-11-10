package com.cloudcost.adapter.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.AccessTokens.IssuedToken;
import com.cloudcost.domain.user.VerifiedIdentity;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 실제 Google 대신 테스트용 RSA 키로 서명한 토큰으로 검증 규칙을 확인한다. */
class AuthAdapterTest {

    private static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
    private static RSAKey googleKey;
    private static RSAKey otherKey;

    @BeforeAll
    static void keys() throws Exception {
        googleKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("google-1").generate();
    }

    private static String googleToken(RSAKey key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .subject("1122334455")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("email", "user@example.com")
                .claim("email_verified", true)
                .claim("name", "홍길동");
    }

    private final GoogleIdTokenVerifier verifier =
            new GoogleIdTokenVerifier(CLIENT_ID, new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())));

    @Nested
    class Google_ID_토큰 {

        @Test
        void 서명_발급처_클라이언트ID_만료_이메일인증이_맞으면_신원을_돌려준다() throws Exception {
            VerifiedIdentity identity = verifier.verify(googleToken(googleKey, validClaims().build()));
            assertThat(identity).isEqualTo(new VerifiedIdentity("google", "1122334455", "user@example.com", "홍길동", null));

            VerifiedIdentity withPicture = verifier.verify(googleToken(googleKey,
                    validClaims().claim("picture", "https://lh3.googleusercontent.com/a/photo").build()));
            assertThat(withPicture.picture()).isEqualTo("https://lh3.googleusercontent.com/a/photo");
        }

        @Test
        void 다른_앱용_토큰은_거부한다() throws Exception {
            assertRejected(googleToken(googleKey, validClaims().audience("someone-else").build()));
        }

        @Test
        void 만료된_토큰은_거부한다() throws Exception {
            Instant past = Instant.now().minus(Duration.ofHours(2));
            assertRejected(googleToken(googleKey, validClaims()
                    .issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(600))).build()));
        }

        @Test
        void Google이_아닌_발급처나_다른_키_서명은_거부한다() throws Exception {
            assertRejected(googleToken(googleKey, validClaims().issuer("https://evil.example.com").build()));
            assertRejected(googleToken(otherKey, validClaims().build()));
            assertRejected(new PlainJWT(validClaims().build()).serialize());
            assertRejected("not-a-token");
        }

        @Test
        void 이메일_인증이_안_된_계정은_거부한다() throws Exception {
            assertRejected(googleToken(googleKey, validClaims().claim("email_verified", false).build()));
        }

        @Test
        void 클라이언트ID가_없으면_설정_오류() {
            GoogleIdTokenVerifier unconfigured =
                    new GoogleIdTokenVerifier("", new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())));
            assertThatThrownBy(() -> unconfigured.verify("x"))
                    .isInstanceOfSatisfying(AuthenticationFailedException.class,
                            e -> assertThat(e.code()).isEqualTo("GOOGLE_LOGIN_NOT_CONFIGURED"));
        }

        private void assertRejected(String token) {
            assertThatThrownBy(() -> verifier.verify(token))
                    .isInstanceOfSatisfying(AuthenticationFailedException.class,
                            e -> assertThat(e.code()).isEqualTo("INVALID_ID_TOKEN"));
        }
    }

    /** 가짜 Google 토큰 엔드포인트(로컬 HTTP 서버)로 인가 코드 교환을 확인한다. */
    @Nested
    class Google_인가_코드 {

        private HttpServer server;
        private final AtomicReference<String> receivedForm = new AtomicReference<>();
        private volatile int status = 200;
        private volatile String responseBody = "{}";

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                receivedForm.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private GoogleIdTokenVerifier verifier(String secret) {
            return new GoogleIdTokenVerifier(CLIENT_ID, secret,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token"),
                    new ImmutableJWKSet<>(new JWKSet(googleKey.toPublicJWK())));
        }

        @Test
        void 코드를_postmessage로_교환하고_받은_ID_토큰을_같은_규칙으로_검증한다() throws Exception {
            responseBody = "{\"access_token\":\"ya29.x\",\"id_token\":\""
                    + googleToken(googleKey, validClaims().claim("picture", "https://lh3.googleusercontent.com/a/p").build())
                    + "\",\"token_type\":\"Bearer\"}";

            VerifiedIdentity identity = verifier("test-secret").verifyAuthCode("4/0Ab-code&x=1");

            assertThat(identity.subject()).isEqualTo("1122334455");
            assertThat(identity.picture()).isEqualTo("https://lh3.googleusercontent.com/a/p");
            assertThat(receivedForm.get().split("&")).containsExactlyInAnyOrder(
                    "code=4%2F0Ab-code%26x%3D1", "client_id=" + CLIENT_ID, "client_secret=test-secret",
                    "redirect_uri=postmessage", "grant_type=authorization_code");
        }

        @Test
        void Google이_코드를_거절하거나_받은_토큰이_틀리면_INVALID_AUTH_CODE() throws Exception {
            status = 400;
            responseBody = "{\"error\":\"invalid_grant\"}";
            assertInvalidCode(verifier("test-secret"));

            status = 200;
            responseBody = "{\"access_token\":\"ya29.x\"}";
            assertInvalidCode(verifier("test-secret"));

            responseBody = "{\"id_token\":\"" + googleToken(otherKey, validClaims().build()) + "\"}";
            assertInvalidCode(verifier("test-secret"));
        }

        @Test
        void 클라이언트_시크릿이_없으면_설정_오류() {
            assertThatThrownBy(() -> verifier("").verifyAuthCode("code"))
                    .isInstanceOfSatisfying(AuthenticationFailedException.class,
                            e -> assertThat(e.code()).isEqualTo("GOOGLE_LOGIN_NOT_CONFIGURED"));
            assertThat(receivedForm.get()).as("Google을 부르지 않는다").isNull();
        }

        private void assertInvalidCode(GoogleIdTokenVerifier v) {
            assertThatThrownBy(() -> v.verifyAuthCode("code"))
                    .isInstanceOfSatisfying(AuthenticationFailedException.class,
                            e -> assertThat(e.code()).isEqualTo("INVALID_AUTH_CODE"));
        }
    }

    @Nested
    class 액세스_토큰 {

        private final String secret = "test-secret-test-secret-test-secret-123";
        private final JwtAccessTokens tokens = new JwtAccessTokens(secret, Duration.ofHours(1));
        private final Instant now = Instant.parse("2026-10-13T00:00:00Z");

        @Test
        void 발급한_토큰은_1시간_동안_사용자ID로_검증된다() {
            IssuedToken token = tokens.issue("user-1", now);
            assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
            assertThat(tokens.verify(token.value(), now.plusSeconds(3599))).contains("user-1");
            assertThat(tokens.verify(token.value(), now.plusSeconds(3600))).isEmpty();
        }

        @Test
        void 위조하거나_다른_키로_만든_토큰은_거부한다() {
            String token = tokens.issue("user-1", now).value();
            String[] parts = token.split("\\.");
            String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"iss\":\"cloudcost-insight\",\"aud\":\"cloudcost-insight-api\",\"sub\":\"user-2\",\"exp\":9999999999}"
                            .getBytes());
            assertThat(tokens.verify(parts[0] + "." + forgedPayload + "." + parts[2], now)).isEmpty();

            JwtAccessTokens otherServer = new JwtAccessTokens("another-secret-another-secret-12345", Duration.ofHours(1));
            assertThat(tokens.verify(otherServer.issue("user-1", now).value(), now)).isEmpty();
            assertThat(tokens.verify("garbage", now)).isEmpty();
        }

        @Test
        void 짧은_서명_키는_거부한다() {
            assertThatThrownBy(() -> new JwtAccessTokens("short", Duration.ofHours(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
