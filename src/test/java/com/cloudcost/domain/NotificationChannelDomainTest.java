package com.cloudcost.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudcost.domain.alert.Delivery;
import com.cloudcost.domain.alert.DiscordWebhook;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationChannelDomainTest {

    static final String URL = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz_-0123";
    static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void discord_com_웹훅_주소만_받는다() {
        assertThat(DiscordWebhook.validate("  " + URL + " ")).isEqualTo(URL);
        for (String bad : new String[] {
            null, "", "http://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz",
            "https://evil.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz",
            "https://discord.com.evil.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz",
            "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz?wait=true",
            "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz/../x",
            "https://169.254.169.254/latest/meta-data"}) {
            assertThatThrownBy(() -> DiscordWebhook.validate(bad))
                .as(String.valueOf(bad))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("https://discord.com/api/webhooks/");
        }
    }

    @Test
    void 가린_주소에는_토큰이_없다() {
        assertThat(DiscordWebhook.mask(URL))
            .isEqualTo("https://discord.com/api/webhooks/…5678/••••")
            .doesNotContain("abcdefghij");
    }

    @Test
    void 실패하면_간격을_늘려_다시_보내고_5번째에_멈춘다() {
        Delivery d = Delivery.pending(UUID.randomUUID(), "o", UUID.randomUUID(), UUID.randomUUID(), NOW);

        Delivery first = d.failed("일시 오류", true, NOW);
        assertThat(first.status()).isEqualTo(Delivery.Status.PENDING);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));

        Delivery second = first.failed("일시 오류", true, NOW);
        assertThat(second.nextAttemptAt()).isEqualTo(NOW.plusSeconds(300));

        Delivery last = second.failed("x", true, NOW).failed("x", true, NOW).failed("x", true, NOW);
        assertThat(last.attempts()).isEqualTo(5);
        assertThat(last.status()).isEqualTo(Delivery.Status.FAILED);
    }

    @Test
    void 다시_보내도_소용없는_실패는_바로_멈추고_성공하면_보낸_시각을_남긴다() {
        Delivery d = Delivery.pending(UUID.randomUUID(), "o", UUID.randomUUID(), UUID.randomUUID(), NOW);

        assertThat(d.failed("웹훅 없음", false, NOW).status()).isEqualTo(Delivery.Status.FAILED);
        Delivery sent = d.sent(NOW);
        assertThat(sent.status()).isEqualTo(Delivery.Status.SENT);
        assertThat(sent.sentAt()).isEqualTo(NOW);
        assertThat(sent.attempts()).isEqualTo(1);
    }
}
