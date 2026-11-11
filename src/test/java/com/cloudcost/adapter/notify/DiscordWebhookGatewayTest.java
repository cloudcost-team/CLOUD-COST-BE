package com.cloudcost.adapter.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.application.port.NotificationChannelGateway.SendResult;
import com.cloudcost.domain.alert.ChannelType;
import com.cloudcost.domain.alert.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

        SendResult r = gateway.send(tampered, "hi");

        assertThat(r.ok()).isFalse();
        assertThat(r.retryable()).isFalse();
    }
}
