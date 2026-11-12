package com.cloudcost.adapter.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.application.port.NotificationChannelGateway.Field;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.application.port.NotificationChannelGateway.Tone;
import com.cloudcost.domain.alert.ChannelType;
import com.cloudcost.domain.alert.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscordWebhookGatewayTest {

    @Test
    void 응답_코드를_다시_보낼지와_한국어_사유로_바꾼다() {
        assertThat(DiscordWebhookGateway.result(204).ok()).isTrue();
        assertThat(DiscordWebhookGateway.result(429)).extracting(SendResult::ok, SendResult::retryable)
            .containsExactly(false, true);
        assertThat(DiscordWebhookGateway.result(503).retryable()).isTrue();
        SendResult gone = DiscordWebhookGateway.result(404);
        assertThat(gone.retryable()).isFalse();
        assertThat(gone.reason()).contains("웹훅을 찾을 수 없어요");
        assertThat(DiscordWebhookGateway.result(400).retryable()).isFalse();
    }

    @Test
    void 저장된_주소가_discord가_아니면_요청하지_않는다() {
        // DB가 바뀌었더라도 보내기 직전에 다시 검사한다 (HttpClient를 null로 넘겨 요청하면 바로 실패하게 한다)
        DiscordWebhookGateway gateway = new DiscordWebhookGateway(null, new ObjectMapper());
        NotificationChannel tampered = new NotificationChannel(UUID.randomUUID(), "o", ChannelType.DISCORD,
            "http://169.254.169.254/latest/meta-data", true, Instant.EPOCH, Instant.EPOCH);

        SendResult r = gateway.send(tampered, new Message("hi", null, Tone.INFO, List.of(), null, null));

        assertThat(r.ok()).isFalse();
        assertThat(r.retryable()).isFalse();
    }

    @Test
    void content_없이_embed_하나로_보내고_링크는_embed_url에_멘션은_막는다() throws Exception {
        Message m = new Message("예산 120% 넘음", "테스트서버의 이번 달 월말 예상 비용이 예산의 120%를 넘었어요.", Tone.DANGER,
            List.of(new Field("연결", "테스트서버"), new Field("월말 예상 비용", "₩65,000"), new Field("원인 서버", null)),
            "사용 시간 × 온디맨드 단가 기준", "https://www.cloud-cost.cloud/dashboard");

        JsonNode body = new ObjectMapper().valueToTree(DiscordWebhookGateway.payload(m));

        assertThat(body.has("content")).as("본문 URL 미리보기 카드가 붙지 않게").isFalse();
        assertThat(body.at("/allowed_mentions/parse").isArray()).isTrue();
        assertThat(body.at("/allowed_mentions/parse").size()).isZero();
        assertThat(body.at("/embeds").size()).isEqualTo(1);
        JsonNode embed = body.at("/embeds/0");
        assertThat(embed.at("/title").asText()).isEqualTo("예산 120% 넘음");
        assertThat(embed.at("/color").asInt()).isEqualTo(DiscordWebhookGateway.RED);
        assertThat(embed.at("/url").asText()).isEqualTo("https://www.cloud-cost.cloud/dashboard");
        assertThat(embed.at("/footer/text").asText()).isEqualTo("사용 시간 × 온디맨드 단가 기준");
        assertThat(embed.at("/fields").size()).as("값이 없는 필드는 뺀다").isEqualTo(2);
        assertThat(embed.at("/fields/1/name").asText()).isEqualTo("월말 예상 비용");
        assertThat(embed.at("/fields/1/value").asText()).isEqualTo("₩65,000");
    }

    @Test
    void 색은_빨강_주황_파랑이고_링크가_없으면_url을_넣지_않는다() {
        assertThat(DiscordWebhookGateway.color(Tone.WARNING)).isEqualTo(DiscordWebhookGateway.ORANGE);
        assertThat(DiscordWebhookGateway.color(Tone.INFO)).isEqualTo(DiscordWebhookGateway.BLUE);
        var embed = (java.util.Map<?, ?>) ((List<?>) DiscordWebhookGateway.payload(
            new Message("t", "d", Tone.INFO, List.of(), null, null)).get("embeds")).getFirst();
        assertThat(embed.containsKey("url")).isFalse();
        assertThat(embed.containsKey("footer")).isFalse();
    }

    @Test
    void embed_전체_글자_수를_6000자_안에_맞춘다() {
        String longText = "가".repeat(5000);
        List<Field> manyFields = java.util.stream.IntStream.range(0, 30)
            .mapToObj(i -> new Field("필드" + i, longText)).toList();
        Message huge = new Message("제".repeat(300), longText, Tone.INFO, manyFields, "f".repeat(3000), null);

        JsonNode embed = new ObjectMapper().valueToTree(DiscordWebhookGateway.payload(huge)).at("/embeds/0");

        int total = embed.at("/title").asText().length() + embed.at("/description").asText().length()
            + embed.at("/footer/text").asText().length();
        for (JsonNode f : embed.at("/fields")) {
            total += f.at("/name").asText().length() + f.at("/value").asText().length();
            assertThat(f.at("/name").asText()).isNotEmpty();
            assertThat(f.at("/value").asText()).isNotEmpty();
        }
        assertThat(total).isLessThanOrEqualTo(DiscordWebhookGateway.MAX_EMBED_TOTAL);
        assertThat(embed.at("/title").asText()).hasSize(DiscordWebhookGateway.MAX_TITLE);
        assertThat(embed.at("/footer/text").asText()).as("청구서가 아니라는 footer는 남긴다")
            .hasSize(DiscordWebhookGateway.MAX_FOOTER);
        assertThat(embed.at("/description").asText()).endsWith("…");
    }

    @Test
    void 합계가_6000자_이하면_자르지_않는다() {
        // 제목 256 + footer 2048 + 설명 3696 = 6000
        Message exact = new Message("제".repeat(256), "가".repeat(3696), Tone.INFO, List.of(), "f".repeat(2048), null);

        JsonNode embed = new ObjectMapper().valueToTree(DiscordWebhookGateway.payload(exact)).at("/embeds/0");

        assertThat(embed.at("/description").asText()).isEqualTo("가".repeat(3696));
    }
}
