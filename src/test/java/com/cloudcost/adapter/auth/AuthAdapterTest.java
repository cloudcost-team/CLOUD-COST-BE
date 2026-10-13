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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
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
            assertThat(identity).isEqualTo(new VerifiedIdentity("google", "1122334455", "user@example.com", "홍길동"));
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
