package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 사용자별 데이터 격리: 다른 사용자의 연결 id로 요청하면 존재 여부를 숨기고 404. */
class OwnerIsolationTest extends IntegrationTestBase {

    @Test
    void 다른_사용자의_계정_데이터는_모든_엔드포인트에서_404() throws Exception {
        aws.instances.add(Fixtures.instance("i-a", "t3.micro", "running", Instant.parse("2026-10-07T00:00:00Z")));
        String accountOfA = activeAccount();
        getJson("/api/v1/aws/accounts/" + accountOfA + "/instances").andExpect(status().isOk());
        int describeCallsBefore = aws.describeCalls;

        String userB = "user-b-" + owner;
        List<String> paths = List.of(
                "/api/v1/aws/accounts/" + accountOfA,
                "/api/v1/aws/accounts/" + accountOfA + "/sync-jobs",
                "/api/v1/aws/accounts/" + accountOfA + "/instances",
                "/api/v1/aws/accounts/" + accountOfA + "/costs",
                "/api/v1/aws/accounts/" + accountOfA + "/costs/daily",
                "/api/v1/aws/accounts/" + accountOfA + "/costs/monthly",
                "/api/v1/aws/accounts/" + accountOfA + "/waste",
                "/api/v1/aws/accounts/" + accountOfA + "/dashboard",
                "/api/v1/aws/accounts/" + accountOfA + "/benchmark");
        for (String path : paths) {
            mvc.perform(as(userB, get(path)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        }
        mvc.perform(as(userB, put("/api/v1/aws/accounts/" + accountOfA + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleArn\":\"" + Fixtures.CUSTOMER_ROLE_ARN + "\"}")))
                .andExpect(status().isNotFound());

        mvc.perform(as(userB, post("/api/v1/aws/accounts/" + accountOfA + "/simulations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));

        mvc.perform(as(userB, put("/api/v1/settings/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"budget80":true,"budget100":true,"budget120":true,"spike":true,"weeklySummary":true,
                         "budgets":[{"accountId":"%s","amount":1,"currency":"USD"}]}
                        """.formatted(accountOfA))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(as(userB, get("/api/v1/settings/alerts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets.length()").value(0));
        mvc.perform(as(userB, get("/api/v1/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(as(userB, post("/api/v1/notifications/" + java.util.UUID.randomUUID() + "/read")))
                .andExpect(status().isNotFound());

        mvc.perform(as(userB, get("/api/v1/aws/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(aws.describeCalls).as("남의 계정으로 AWS를 부르지 않는다").isEqualTo(describeCallsBefore);
    }

    @Test
    void 헤더가_올바르지_않으면_401() throws Exception {
        mvc.perform(get("/api/v1/aws/accounts").header("X-Owner-Id", "bad owner!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
