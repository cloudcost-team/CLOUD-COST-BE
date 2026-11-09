package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.FakeGoogleIdentityVerifier;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** 리프레시 토큰: HttpOnly 쿠키 발급 → rotation → 재사용 감지 → 로그아웃 (ADR-017) */
class RefreshTokenTest extends IntegrationTestBase {

    private static final String COOKIE = "cci_refresh";

    private MockHttpServletResponse login() throws Exception {
        String token = FakeGoogleIdentityVerifier.token("r-" + UUID.randomUUID(), "refresh@example.com");
        return mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private MockHttpServletResponse refresh(String cookie) throws Exception {
        var request = post("/api/v1/auth/refresh");
        if (cookie != null) {
            request.cookie(new Cookie(COOKIE, cookie));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private static String cookieValue(MockHttpServletResponse response) {
        return response.getCookie(COOKIE).getValue();
    }

    @Test
    void 로그인하면_HttpOnly_쿠키로만_리프레시_토큰을_준다() throws Exception {
        MockHttpServletResponse res = login();
        String setCookie = res.getHeader("Set-Cookie");
        assertThat(setCookie).contains(COOKIE + "=", "HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1/auth",
                "Max-Age=" + Duration.ofDays(14).toSeconds());
        assertThat(res.getContentAsString()).doesNotContain(cookieValue(res)).doesNotContain("refreshToken");
    }

    @Test
    void 리프레시하면_새_액세스_토큰과_새_쿠키를_받고_새_토큰으로_API를_쓴다() throws Exception {
        String first = cookieValue(login());
        clock.advance(Duration.ofHours(2)); // 액세스 토큰(1시간)은 이미 만료

        MockHttpServletResponse res = refresh(first);
        assertThat(res.getStatus()).isEqualTo(200);
        String second = cookieValue(res);
        assertThat(second).isNotBlank().isNotEqualTo(first);

        String access = json.readTree(res.getContentAsString()).at("/accessToken").asText();
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("refresh@example.com"));

        assertThat(refresh(second).getStatus()).isEqualTo(200);
    }

    @Test
    void 이미_쓴_토큰이_다시_오면_같은_로그인의_토큰을_모두_무효화한다() throws Exception {
        String first = cookieValue(login());
        String second = cookieValue(refresh(first));

        MockHttpServletResponse reused = refresh(first);
        assertThat(reused.getStatus()).isEqualTo(401);
        assertThat(reused.getContentAsString()).contains("REFRESH_TOKEN_REUSED");
        assertThat(reused.getCookie(COOKIE).getMaxAge()).isZero();

        // 정상 사용자가 가진 최신 토큰도 무효
        assertThat(refresh(second).getStatus()).isEqualTo(401);
    }

    @Test
    void 쿠키가_없거나_모르는_값이거나_만료되면_401() throws Exception {
        assertThat(refresh(null).getContentAsString()).contains("INVALID_REFRESH_TOKEN");
        assertThat(refresh(null).getStatus()).isEqualTo(401);
        assertThat(refresh("forged").getStatus()).isEqualTo(401);

        String token = cookieValue(login());
        clock.advance(Duration.ofDays(14));
        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void 로그아웃하면_쿠키를_지우고_리프레시할_수_없다() throws Exception {
        String token = cookieValue(login());

        MockHttpServletResponse res = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertThat(res.getCookie(COOKIE).getMaxAge()).isZero();

        assertThat(refresh(token).getStatus()).isEqualTo(401);
        // 쿠키 없이 로그아웃해도 오류가 아니다
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }
}
