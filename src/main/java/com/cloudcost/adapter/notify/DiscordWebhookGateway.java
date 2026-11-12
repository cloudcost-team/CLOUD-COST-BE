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

    /** {"embeds":[{title, description, color, url?, fields, footer?}], "allowed_mentions":{"parse":[]}} */
    static Map<String, Object> payload(Message message) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", cut(message.title(), MAX_TITLE));
        if (message.description() != null && !message.description().isBlank()) {
            embed.put("description", cut(message.description(), MAX_DESCRIPTION));
        }
        embed.put("color", color(message.tone()));
        if (message.url() != null && !message.url().isBlank()) {
            embed.put("url", message.url());
        }
        List<Map<String, Object>> fields = message.fields().stream()
            .filter(f -> f.value() != null && !f.value().isBlank())
            .limit(MAX_FIELDS)
            .map(f -> Map.<String, Object>of(
                "name", cut(f.name(), MAX_FIELD_NAME), "value", cut(f.value(), MAX_FIELD_VALUE), "inline", true))
            .toList();
        if (!fields.isEmpty()) {
            embed.put("fields", fields);
        }
        if (message.footer() != null && !message.footer().isBlank()) {
            embed.put("footer", Map.of("text", cut(message.footer(), MAX_FOOTER)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("embeds", List.of(embed));
        body.put("allowed_mentions", Map.of("parse", List.of()));
        return body;
    }

    static int color(NotificationChannelGateway.Tone tone) {
        return switch (tone == null ? NotificationChannelGateway.Tone.INFO : tone) {
            case DANGER -> RED;
            case WARNING -> ORANGE;
            case INFO -> BLUE;
        };
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
