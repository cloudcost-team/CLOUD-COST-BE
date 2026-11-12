package com.cloudcost.adapter.notify;

import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.application.port.NotificationChannelGateway.Message;
import com.cloudcost.domain.alert.DiscordWebhook;
import com.cloudcost.domain.alert.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discord 웹훅 발송 (ADR-019). 보내기 직전에 주소를 다시 검사하고(discord.com/api/webhooks/만), 리다이렉트를 따라가지 않는다.
 * 메시지는 content 없이 embed 1개(제목·색 막대·필드·footer, 링크는 embed url)로 보내 본문 URL의 미리보기 카드가 붙지 않게 한다.
 * 메시지 안의 @everyone·@here 같은 멘션은 막는다 (allowed_mentions 비움). 로그에는 웹훅 주소를 남기지 않는다.
 */
@Component
class DiscordWebhookGateway implements NotificationChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookGateway.class);
    // Discord embed 글자 수 상한
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 4096;
    static final int MAX_FIELDS = 25;
    static final int MAX_FIELD_NAME = 256;
    static final int MAX_FIELD_VALUE = 1024;
    static final int MAX_FOOTER = 2048;
    /** 제목·설명·필드 이름과 값·footer 글자 수 합계 */
    static final int MAX_EMBED_TOTAL = 6000;

    static final int RED = 0xED4245;
    static final int ORANGE = 0xF0A030;
    static final int BLUE = 0x3B82F6;

    private final HttpClient http;
    private final ObjectMapper mapper;

    @Autowired
    DiscordWebhookGateway(ObjectMapper mapper) {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), mapper);
    }

    DiscordWebhookGateway(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public SendResult send(NotificationChannel channel, Message message) {
        String url;
        try {
            url = DiscordWebhook.validate(channel.webhookUrl());
        } catch (RuntimeException e) {
            return SendResult.failure("Discord 웹훅 주소가 올바르지 않아요. 주소를 다시 등록해 주세요.", false);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload(message))))
                .build();
        } catch (IOException e) {
            return SendResult.failure("보낼 메시지를 만들지 못했어요.", false);
        }
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return result(response.statusCode());
        } catch (IOException e) {
            log.warn("Discord webhook send failed: channel={} error={}", channel.id(), e.getClass().getSimpleName());
            return SendResult.failure("Discord에 연결하지 못했어요. 잠시 뒤 다시 보낼게요.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.failure("보내는 중에 중단됐어요. 잠시 뒤 다시 보낼게요.", true);
        }
    }

    /**
     * {"embeds":[{title, description, color, url?, fields, footer?}], "allowed_mentions":{"parse":[]}}.
     * 항목별 상한과 함께 embed 전체 글자 수(제목·설명·필드 이름과 값·footer 합계)를 {@link #MAX_EMBED_TOTAL} 안에 맞춘다.
     * 넘으면 Discord가 400으로 거부하고 다시 보내지 않으므로, 제목 → footer → 설명 → 필드 순으로 남은 글자 수만큼 자른다.
     */
    static Map<String, Object> payload(Message message) {
        Budget budget = new Budget(MAX_EMBED_TOTAL);
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", budget.take(message.title(), MAX_TITLE));
        String footer = present(message.footer()) ? budget.take(message.footer(), MAX_FOOTER) : null;
        if (present(message.description()) && budget.left() > 0) {
            embed.put("description", budget.take(message.description(), MAX_DESCRIPTION));
        }
        embed.put("color", color(message.tone()));
        if (present(message.url())) {
            embed.put("url", message.url());
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (NotificationChannelGateway.Field f : message.fields()) {
            if (fields.size() == MAX_FIELDS || budget.left() < 2) {
                break;
            }
            if (!present(f.name()) || !present(f.value())) {
                continue;
            }
            String name = budget.take(f.name(), Math.min(MAX_FIELD_NAME, budget.left() - 1));
            fields.add(Map.of("name", name, "value", budget.take(f.value(), MAX_FIELD_VALUE), "inline", true));
        }
        if (!fields.isEmpty()) {
            embed.put("fields", fields);
        }
        if (footer != null) {
            embed.put("footer", Map.of("text", footer));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("embeds", List.of(embed));
        body.put("allowed_mentions", Map.of("parse", List.of()));
        return body;
    }

    /** embed 전체 글자 수 중 남은 몫 */
    private static final class Budget {
        private int left;

        Budget(int total) {
            this.left = total;
        }

        int left() {
            return left;
        }

        /** 항목 상한과 남은 몫 중 작은 쪽에 맞춰 자르고 그만큼 쓴다 */
        String take(String s, int max) {
            String result = cut(s, Math.min(max, left));
            left -= result.length();
            return result;
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    static int color(NotificationChannelGateway.Tone tone) {
        return switch (tone == null ? NotificationChannelGateway.Tone.INFO : tone) {
            case DANGER -> RED;
            case WARNING -> ORANGE;
            case INFO -> BLUE;
        };
    }

    private static String cut(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return max <= 0 ? "" : s.substring(0, max - 1) + "…";
    }

    static SendResult result(int status) {
        if (status >= 200 && status < 300) {
            return SendResult.success();
        }
        if (status == 429) {
            return SendResult.failure("Discord 요청 제한에 걸렸어요. 잠시 뒤 다시 보낼게요.", true);
        }
        if (status == 401 || status == 403 || status == 404) {
            return SendResult.failure("Discord 웹훅을 찾을 수 없어요. 웹훅이 삭제되지 않았는지 확인하고 주소를 다시 등록해 주세요.", false);
        }
        if (status >= 500) {
            return SendResult.failure("Discord 서버에 문제가 있어요. 잠시 뒤 다시 보낼게요.", true);
        }
        return SendResult.failure("Discord가 메시지를 받지 않았어요 (HTTP " + status + ").", false);
    }
}
