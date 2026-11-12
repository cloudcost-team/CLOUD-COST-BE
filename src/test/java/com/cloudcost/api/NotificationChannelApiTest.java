package com.cloudcost.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.NotificationDispatchService;
import com.cloudcost.application.NotificationDispatchService.Result;
import com.cloudcost.application.port.NotificationChannelGateway.Field;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.application.port.NotificationChannelGateway.Tone;
import com.cloudcost.fakes.Fixtures;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 외부 알림 채널과 발송 (ADR-019). 구성은 NotificationApiTest와 같다: 예산 $30이면 월말 예상 103.2% → 예산 100% 알림,
 * 지난주 요약 알림까지 2건이 생긴다.
 */
class NotificationChannelApiTest extends IntegrationTestBase {

    static final String URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_-0123";
    static final String TOKEN = "abcdefghijklmnopqrstuvwxyz_-0123";
    static final String CHANNELS = "/api/v1/settings/alerts/channels";

    @Autowired NotificationDispatchService dispatcher;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.cloudcost.adapter.persistence.PlaintextWebhookEncryptor plaintextEncryptor;

    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        accountId = activeAccount();
        aws.instances.add(Fixtures.instance("i-run", "t3.medium", "running", Instant.parse("2026-09-01T00:00:00Z")));
        aws.addHours("i-run", Instant.parse("2026-09-01T00:00:00Z"), 37 * 24 + 3, 60);
        priceCatalog.hourlyByType.put("t3.medium", new BigDecimal("0.0416"));
    }

    private String createChannel(String url) throws Exception {
        JsonNode created = body(call(post(CHANNELS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DISCORD\",\"webhookUrl\":\"" + url + "\"}"))
                .andExpect(status().isCreated()));
        return created.at("/channelId").asText();
    }

    private void budgetAlerts() throws Exception {
        call(put("/api/v1/aws/accounts/" + accountId + "/budget").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":30,\"currency\":\"USD\"}")).andExpect(status().isOk());
        getJson("/api/v1/notifications/unread-count").andExpect(status().isOk());
    }

    @Test
    void 등록_목록_수정_삭제하고_주소는_가려서_돌려준다() throws Exception {
        String raw = call(post(CHANNELS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\" " + URL + " \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DISCORD"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.target").value("https://discord.com/api/webhooks/…5678/••••"))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(TOKEN);
        String id = json.readTree(raw).at("/channelId").asText();

        JsonNode list = body(getJson(CHANNELS).andExpect(status().isOk()));
        assertThat(list.at("/items").size()).isEqualTo(1);
        assertThat(list.at("/maxChannels").asInt()).isEqualTo(5);
        assertThat(list.toString()).doesNotContain(TOKEN);

        call(put(CHANNELS + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.target").value("https://discord.com/api/webhooks/…5678/••••"));

        call(delete(CHANNELS + "/" + id)).andExpect(status().isNoContent());
        assertThat(body(getJson(CHANNELS)).at("/items").size()).isZero();
    }

    @Test
    void 웹훅_주소는_DB에_암호화해_저장하고_보낼_때만_푼다() throws Exception {
        String id = createChannel(URL);

        String stored = jdbc.queryForObject("SELECT webhook_url FROM notification_channels WHERE id = ?", String.class,
                java.util.UUID.fromString(id));
        assertThat(stored).startsWith("v1:").doesNotContain(TOKEN).doesNotContain("discord.com");
        String again = createChannel(URL);
        assertThat(jdbc.queryForObject("SELECT webhook_url FROM notification_channels WHERE id = ?", String.class,
                java.util.UUID.fromString(again))).as("같은 주소도 매번 다르게 저장된다 (무작위 IV)").isNotEqualTo(stored);

        call(post(CHANNELS + "/" + id + "/test")).andExpect(status().isOk());
        assertThat(channelGateway.sent.getLast().channel().webhookUrl()).as("보낼 때는 원래 주소").isEqualTo(URL);
    }

    @Test
    void 암호화_전에_저장한_평문_주소도_그대로_읽힌다() throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO notification_channels (id, owner_id, type, webhook_url, enabled, created_at, updated_at) "
                + "VALUES (?, ?, 'DISCORD', ?, TRUE, ?, ?)", id, owner, URL,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        JsonNode list = body(getJson(CHANNELS).andExpect(status().isOk()));
        assertThat(list.toString()).contains("webhooks/…5678/").doesNotContain(TOKEN);
        call(post(CHANNELS + "/" + id + "/test")).andExpect(status().isOk());
        assertThat(channelGateway.sent.getLast().channel().webhookUrl()).isEqualTo(URL);
    }

    @Test
    void discord_웹훅이_아닌_주소와_6번째_채널은_받지_않는다() throws Exception {
        call(post(CHANNELS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\"https://evil.example.com/api/webhooks/1/abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_WEBHOOK_URL"));
        call(post(CHANNELS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SLACK\",\"webhookUrl\":\"" + URL + "\"}"))
                .andExpect(status().isBadRequest());

        for (int i = 0; i < 5; i++) {
            createChannel(URL);
        }
        call(post(CHANNELS).contentType(MediaType.APPLICATION_JSON).content("{\"webhookUrl\":\"" + URL + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHANNEL_LIMIT_EXCEEDED"));
    }

    @Test
    void 남의_채널은_보거나_바꾸거나_보낼_수_없다() throws Exception {
        String id = createChannel(URL);

        mvc.perform(as("someone-else", put(CHANNELS + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHANNEL_NOT_FOUND"));
        mvc.perform(as("someone-else", post(CHANNELS + "/" + id + "/test"))).andExpect(status().isNotFound());
        mvc.perform(as("someone-else", delete(CHANNELS + "/" + id))).andExpect(status().isNotFound());
        assertThat(body(mvc.perform(as("someone-else", get(CHANNELS)))).at("/items").size()).isZero();
        assertThat(channelGateway.sent).isEmpty();
    }

    @Test
    void 테스트_발송_결과를_한국어로_돌려준다() throws Exception {
        String id = createChannel(URL);

        call(post(CHANNELS + "/" + id + "/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertThat(channelGateway.sent).hasSize(1);
        Message test = channelGateway.sent.getFirst().message();
        assertThat(test.title()).isEqualTo("테스트 알림");
        assertThat(test.description()).contains("켜 둔 알림이 생기면 이 채널로 보내 드려요");

        channelGateway.next = SendResult.failure("Discord 웹훅을 찾을 수 없어요.", false);
        call(post(CHANNELS + "/" + id + "/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.reason").value("Discord 웹훅을 찾을 수 없어요."));
    }

    @Test
    void 새_알림을_채널로_한_번씩_보내고_목록에_발송_상태를_보여_준다() throws Exception {
        createChannel(URL);
        budgetAlerts();

        Result first = dispatcher.dispatch();
        assertThat(first.queued()).isEqualTo(2);
        assertThat(first.sent()).isEqualTo(2);
        // 만들 때 저장한 예산·월말 예상이 embed 필드로 나간다
        Message budget100 = channelGateway.sent.stream().map(s -> s.message())
                .filter(m -> m.title().equals("예산 100% 넘음")).findFirst().orElseThrow();
        assertThat(budget100.description()).isEqualTo("이름 없는 연결의 이번 달 월말 예상 비용이 예산의 100%를 넘었어요.");
        assertThat(budget100.tone()).isEqualTo(Tone.DANGER);
        assertThat(budget100.fields()).extracting(Field::name).containsExactly("연결", "월말 예상 비용", "예산 금액");
        assertThat(budget100.fields()).allSatisfy(f -> assertThat(f.value()).isNotBlank());
        assertThat(budget100.footer()).isEqualTo("사용 시간 × 온디맨드 단가 기준");
        assertThat(budget100.url()).isEqualTo("https://www.cloud-cost.cloud/dashboard");

        Result again = dispatcher.dispatch();
        assertThat(again.queued() + again.sent()).as("이미 보낸 알림은 다시 보내지 않는다").isZero();

        JsonNode items = body(getJson("/api/v1/notifications")).at("/items");
        for (JsonNode item : items) {
            assertThat(item.at("/deliveries").size()).isEqualTo(1);
            assertThat(item.at("/deliveries/0/status").asText()).isEqualTo("SENT");
            assertThat(item.at("/deliveries/0/statusLabel").asText()).isEqualTo("보냄");
            assertThat(item.toString()).doesNotContain(TOKEN);
        }
    }

    @Test
    void 채널_등록_전에_생긴_알림과_꺼진_채널로는_보내지_않는다() throws Exception {
        budgetAlerts();
        clock.set(NOW.plusSeconds(60));
        String id = createChannel(URL);

        assertThat(dispatcher.dispatch().queued()).isZero();

        // 다음 주 요약은 채널 등록 뒤에 생기지만, 채널을 끄면 보내지 않는다
        call(put(CHANNELS + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        clock.set(Instant.parse("2026-10-12T03:00:00Z"));
        getJson("/api/v1/notifications/unread-count").andExpect(status().isOk());
        Result r = dispatcher.dispatch();
        assertThat(r.sent()).isZero();
        assertThat(channelGateway.sent).isEmpty();
    }

    @Test
    void 보내지_못하면_간격을_두고_다시_보낸다() throws Exception {
        createChannel(URL);
        budgetAlerts();
        channelGateway.next = SendResult.failure("Discord 서버에 문제가 있어요. 잠시 뒤 다시 보낼게요.", true);

        assertThat(dispatcher.dispatch().failed()).isEqualTo(2);
        JsonNode delivery = body(getJson("/api/v1/notifications")).at("/items/0/deliveries/0");
        assertThat(delivery.at("/status").asText()).isEqualTo("PENDING");
        assertThat(delivery.at("/attempts").asInt()).isEqualTo(1);
        assertThat(delivery.at("/lastError").asText()).contains("Discord 서버");

        channelGateway.next = SendResult.success();
        assertThat(dispatcher.dispatch().sent()).as("1분이 지나기 전에는 다시 보내지 않는다").isZero();

        clock.set(NOW.plusSeconds(61));
        assertThat(dispatcher.dispatch().sent()).isEqualTo(2);
        assertThat(body(getJson("/api/v1/notifications")).at("/items/0/deliveries/0/status").asText())
                .isEqualTo("SENT");
    }

    @Test
    void 발송_기록이_있어도_연결과_채널을_지울_수_있다() throws Exception {
        String channel = createChannel(URL);
        budgetAlerts();
        dispatcher.dispatch();

        call(delete(CHANNELS + "/" + channel)).andExpect(status().isNoContent());
        createChannel(URL);
        clock.set(NOW.plusSeconds(1));
        call(delete("/api/v1/aws/accounts/" + accountId)).andExpect(status().isOk());
        assertThat(body(getJson("/api/v1/notifications")).at("/items").size()).isZero();
    }

    /** 다른 키로 암호화한 값 = CCI_SECRET_KEY가 바뀐 뒤의 행 */
    private java.util.UUID unreadableChannel() {
        java.util.UUID id = java.util.UUID.randomUUID();
        String foreign = com.cloudcost.adapter.persistence.SecretCipher
                .of("ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=").encrypt(URL);
        jdbc.update("INSERT INTO notification_channels (id, owner_id, type, webhook_url, enabled, created_at, updated_at) "
                + "VALUES (?, ?, 'DISCORD', ?, TRUE, ?, ?)", id, owner, foreign,
                java.sql.Timestamp.from(NOW.minusSeconds(1)), java.sql.Timestamp.from(NOW.minusSeconds(1)));
        return id;
    }

    @Test
    void 주소를_못_푸는_채널이_있어도_다른_채널로는_보내고_그_채널만_실패로_끝낸다() throws Exception {
        java.util.UUID broken = unreadableChannel();
        createChannel(URL);
        budgetAlerts();

        Result r = dispatcher.dispatch();
        assertThat(r.sent()).as("정상 채널은 보낸다").isEqualTo(2);
        assertThat(r.failed()).as("못 푸는 채널은 재시도 없이 실패").isEqualTo(2);
        assertThat(channelGateway.sent).allMatch(s -> URL.equals(s.channel().webhookUrl()));
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE channel_id = ? "
                + "AND status = 'PENDING'", Integer.class, broken);
        assertThat(pending).as("다음 실행에서 같은 행에 다시 걸리지 않는다").isZero();
    }

    @Test
    void 주소를_못_푸는_채널이_있어도_목록_등록은_되고_주소를_다시_넣으면_고쳐진다() throws Exception {
        java.util.UUID broken = unreadableChannel();

        JsonNode list = body(getJson(CHANNELS).andExpect(status().isOk()));
        assertThat(list.at("/items").size()).isEqualTo(1);
        createChannel(URL);
        assertThat(body(call(post(CHANNELS + "/" + broken + "/test")).andExpect(status().isOk())).toString())
                .contains("다시 입력");

        // 켜기·끄기만 바꿔도 저장된 암호문은 그대로 (null로 덮지 않는다)
        call(put(CHANNELS + "/" + broken).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT webhook_url FROM notification_channels WHERE id = ?", String.class, broken))
                .startsWith("v1:");

        call(put(CHANNELS + "/" + broken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"webhookUrl\":\"" + URL + "\",\"enabled\":true}")).andExpect(status().isOk());
        call(post(CHANNELS + "/" + broken + "/test")).andExpect(status().isOk());
        assertThat(channelGateway.sent.getLast().channel().webhookUrl()).isEqualTo(URL);
    }

    @Test
    void 시작할_때_평문으로_남은_주소를_암호화한다() throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO notification_channels (id, owner_id, type, webhook_url, enabled, created_at, updated_at) "
                + "VALUES (?, ?, 'DISCORD', ?, TRUE, ?, ?)", id, owner, URL,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        assertThat(plaintextEncryptor.encryptPlaintext()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT webhook_url FROM notification_channels WHERE id = ?", String.class, id))
                .startsWith("v1:").doesNotContain(TOKEN);
        assertThat(plaintextEncryptor.encryptPlaintext()).as("이미 암호화된 행은 그대로").isZero();
        call(post(CHANNELS + "/" + id + "/test")).andExpect(status().isOk());
        assertThat(channelGateway.sent.getLast().channel().webhookUrl()).isEqualTo(URL);
    }
}
