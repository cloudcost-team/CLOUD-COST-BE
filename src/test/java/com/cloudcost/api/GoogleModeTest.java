package com.cloudcost.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.FakeGoogleIdentityVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** 배포 모드(AUTH_MODE=google): 로그인 토큰 없이는 아무 데이터도 볼 수 없고 X-Owner-Id는 통하지 않는다. */
@TestPropertySource(properties = {
    "cci.auth.mode=google",
    "cci.auth.google-client-id=test-client.apps.googleusercontent.com",
    "cci.auth.jwt-secret=test-secret-test-secret-test-secret-123"
})
class GoogleModeTest extends IntegrationTestBase {

    @Test
    void 토큰이_없으면_401이고_X_Owner_Id는_무시된다() throws Exception {
        mvc.perform(get("/api/v1/aws/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("LOGIN_REQUIRED"));
        mvc.perform(get("/api/v1/aws/accounts").header("X-Owner-Id", "someone"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인하면_토큰으로_사용할_수_있다() throws Exception {
        String token = body(mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + FakeGoogleIdentityVerifier.token("g-mode", "mode@example.com") + "\"}"))
                .andExpect(status().isOk())).at("/accessToken").asText();

        mvc.perform(get("/api/v1/aws/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/config"))
                .andExpect(jsonPath("$.authMode").value("google"))
                .andExpect(jsonPath("$.googleClientId").value("test-client.apps.googleusercontent.com"));
    }
}
