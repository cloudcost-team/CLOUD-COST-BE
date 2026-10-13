package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.FakeGoogleIdentityVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Google 로그인 → 액세스 토큰 → 토큰의 사용자로 데이터가 격리되는지 (로컬 dev 모드에서도 토큰이 있으면 토큰이 우선) */
class GoogleLoginTest extends IntegrationTestBase {

    private JsonNode login(String subject, String email) throws Exception {
        return body(mvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"" + FakeGoogleIdentityVerifier.token(subject, email) + "\"}"))
                .andExpect(status().isOk()));
    }

    private String bearer(JsonNode login) {
        return "Bearer " + login.at("/accessToken").asText();
    }

    @Test
    void 처음_로그인하면_가입되고_다시_로그인하면_같은_사용자다() throws Exception {
        String sub = "g-" + UUID.randomUUID();
        JsonNode first = login(sub, "first@example.com");
        assertThat(first.at("/newUser").asBoolean()).isTrue();
        assertThat(first.at("/tokenType").asText()).isEqualTo("Bearer");
        assertThat(first.at("/user/provider").asText()).isEqualTo("google");

        JsonNode second = login(sub, "changed@example.com");
        assertThat(second.at("/newUser").asBoolean()).isFalse();
        assertThat(second.at("/user/id").asText()).isEqualTo(first.at("/user/id").asText());

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("changed@example.com"));
    }

    @Test
    void 토큰의_사용자로_데이터가_격리된다() throws Exception {
        JsonNode alice = login("alice-" + UUID.randomUUID(), "alice@example.com");
        JsonNode bob = login("bob-" + UUID.randomUUID(), "bob@example.com");

        String accountId = body(mvc.perform(post("/api/v1/aws/accounts").header("Authorization", bearer(alice)))
                .andExpect(status().isCreated())).at("/account/accountId").asText();

        mvc.perform(get("/api/v1/aws/accounts/" + accountId).header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/aws/accounts/" + accountId).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        // 토큰이 있으면 X-Owner-Id로 다른 사람을 흉내 낼 수 없다
        mvc.perform(get("/api/v1/aws/accounts/" + accountId)
                        .header("Authorization", bearer(bob))
                        .header("X-Owner-Id", alice.at("/user/id").asText()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 잘못된_토큰과_잘못된_Google_로그인은_401() throws Exception {
        mvc.perform(get("/api/v1/aws/accounts").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_TOKEN"));
        mvc.perform(get("/api/v1/aws/accounts").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"not-google\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_ID_TOKEN"));
        mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 로그인_화면과_설정은_인증_없이_열린다() throws Exception {
        mvc.perform(get("/api/v1/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("dev"));
        mvc.perform(get("/login.html")).andExpect(status().isOk());
    }
}
