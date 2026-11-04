package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 서비스 안 알림 (#35). NOW = 2026-10-08(목) 12:00 KST. DashboardApiTest와 같은 구성: i-run(t3.medium $0.0416)이 9/1부터 켜져 있어
 * 하루 $0.9984, 월말 예상 $30.95. 지난주(9/28~10/4)와 전주 모두 $6.9888 = ₩9,085 (환율 1300).
 */
class NotificationApiTest extends IntegrationTestBase {

    private static final String NAME = "이름 없는 연결";

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-09-01T00:00:00Z"), 37 * 24 + 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private void putBudget(String amount) throws Exception {
        call(put("/api/v1/aws/accounts/" + accountId + "/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"currency\":\"USD\"}")).andExpect(status().isOk());
    }

    private long unread() throws Exception {
        return body(getJson("/api/v1/notifications/unread-count").andExpect(status().isOk())).at("/unreadCount").asLong();
    }

    private JsonNode list(String query) throws Exception {
        return body(getJson("/api/v1/notifications" + query).andExpect(status().isOk()));
    }

    @Test
    void 조회할_때_조건을_확인해_예산과_주간_요약_알림을_한_번만_만든다() throws Exception {
        putBudget("30"); // 월말 예상 $30.95 = 103.2%

        assertThat(unread()).isEqualTo(2);
        assertThat(unread()).as("다시 조회해도 같은 알림을 또 만들지 않는다").isEqualTo(2);

        JsonNode page = list("");
        assertThat(page.at("/unreadCount").asLong()).isEqualTo(2);
        assertThat(page.at("/nextCursor").isNull()).isTrue();
        assertThat(page.at("/items").findValuesAsText("message")).containsExactlyInAnyOrder(
                NAME + "의 이번 달 예상 비용이 예산의 100%를 넘었어요",
                "지난주 추정 비용 ₩9,085 · 전주와 비슷해요");
        assertThat(page.at("/items").findValuesAsText("accountName")).containsOnly(NAME);
        assertThat(page.at("/items").findValuesAsText("typeLabel")).containsExactlyInAnyOrder("예산", "주간 요약");
        assertThat(page.at("/notes").toString()).contains("서비스 안 알림창");

        putBudget("25"); // 123.8% → 120% 기준은 새 알림
        assertThat(unread()).isEqualTo(3);
        assertThat(list("").at("/items").findValuesAsText("message"))
                .contains(NAME + "의 이번 달 예상 비용이 예산의 120%를 넘었어요");
    }

    @Test
    void 다음_주가_되면_주간_요약을_새로_만든다() throws Exception {
        assertThat(unread()).isEqualTo(1);

        clock.set(Instant.parse("2026-10-12T03:00:00Z")); // 10/12(월). 수집 기록은 10/8 12:00까지 → 10/5~10/11은 3.5일치
        assertThat(unread()).isEqualTo(2);
        assertThat(list("").at("/items").findValuesAsText("message"))
                .contains("지난주 추정 비용 ₩4,543 · 전주보다 50% 줄었어요");
    }

    @Test
    void 읽음_처리와_커서_페이지() throws Exception {
        putBudget("25");
        assertThat(unread()).isEqualTo(2);

        JsonNode first = list("?limit=1");
        assertThat(first.at("/items").size()).isEqualTo(1);
        String cursor = first.at("/nextCursor").asText();
        JsonNode second = list("?limit=1&cursor=" + cursor);
        assertThat(second.at("/items").size()).isEqualTo(1);
        assertThat(second.at("/nextCursor").isNull()).isTrue();
        Set<String> ids = new HashSet<>();
        ids.add(first.at("/items/0/id").asText());
        ids.add(second.at("/items/0/id").asText());
        assertThat(ids).as("두 페이지가 겹치지 않는다").hasSize(2);

        String id = first.at("/items/0/id").asText();
        mvc.perform(as("someone-else", post("/api/v1/notifications/" + id + "/read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(unread()).as("남이 읽음 처리할 수 없다").isEqualTo(2);

        call(post("/api/v1/notifications/" + id + "/read")).andExpect(status().isNoContent());
        call(post("/api/v1/notifications/" + id + "/read")).andExpect(status().isNoContent()); // 다시 읽어도 204
        assertThat(unread()).isEqualTo(1);
        assertThat(list("").at("/items").findValues("read").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);

        call(post("/api/v1/notifications/read-all")).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        getJson("/api/v1/notifications?cursor=broken").andExpect(status().isBadRequest());
        for (String raw : new String[] {"1.1000000000:" + id, "9223372036854775807.999999999:" + id, "-1.-5:" + id}) {
            String bad = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            getJson("/api/v1/notifications?cursor=" + bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        getJson("/api/v1/notifications?limit=51").andExpect(status().isBadRequest());
        mvc.perform(as("someone-else", get("/api/v1/notifications"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 알림_설정은_기본_모두_켜짐이고_끄면_만들지_않으며_연결별_예산을_함께_저장한다() throws Exception {
        JsonNode defaults = body(getJson("/api/v1/settings/alerts").andExpect(status().isOk()));
        assertThat(defaults.at("/budget80").asBoolean()).isTrue();
        assertThat(defaults.at("/weeklySummary").asBoolean()).isTrue();
        assertThat(defaults.at("/channel").asText()).isEqualTo("IN_APP");
        assertThat(defaults.at("/budgets/0/accountId").asText()).isEqualTo(accountId);
        assertThat(defaults.at("/budgets/0/name").asText()).isEqualTo(NAME);
        assertThat(defaults.at("/budgets/0/budget").isNull()).isTrue();

        JsonNode saved = body(call(put("/api/v1/settings/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                {"budget80":false,"budget100":false,"budget120":false,"spike":false,"weeklySummary":false,
                 "budgets":[{"accountId":"%s","amount":25,"currency":"USD"}]}
                """.formatted(accountId))).andExpect(status().isOk()));
        assertThat(saved.at("/budget100").asBoolean()).isFalse();
        assertThat(saved.at("/budgets/0/budget/amount").decimalValue()).isEqualByComparingTo("25");

        assertThat(unread()).as("모두 끄면 알림을 만들지 않는다").isZero();

        call(put("/api/v1/settings/alerts").contentType(MediaType.APPLICATION_JSON).content("""
                {"budget80":true,"budget100":true,"budget120":true,"spike":true,"weeklySummary":true,
                 "budgets":[{"accountId":"%s","amount":null}]}
                """.formatted(accountId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.budgets[0].budget").doesNotExist());
    }

    @Test
    void 설정에_남의_연결이_섞이면_아무것도_바꾸지_않고_404다() throws Exception {
        String request = """
                {"budget80":false,"budget100":true,"budget120":true,"spike":true,"weeklySummary":true,
                 "budgets":[{"accountId":"%s","amount":1,"currency":"USD"}]}
                """.formatted(accountId);
        mvc.perform(as("someone-else", put("/api/v1/settings/alerts")
                        .contentType(MediaType.APPLICATION_JSON).content(request)))
                .andExpect(status().isNotFound());
        assertThat(body(mvc.perform(as("someone-else", get("/api/v1/settings/alerts")))).at("/budget80").asBoolean())
                .isTrue();
        assertThat(body(getJson("/api/v1/settings/alerts")).at("/budgets/0/budget").isNull()).isTrue();

        call(put("/api/v1/settings/alerts").contentType(MediaType.APPLICATION_JSON).content("{\"budget80\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 연결을_지우면_그_연결의_알림도_지운다() throws Exception {
        putBudget("30");
        assertThat(unread()).isEqualTo(2);

        call(delete("/api/v1/aws/accounts/" + accountId)).andExpect(status().isOk());

        assertThat(unread()).isZero();
    }
}
