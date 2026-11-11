package com.cloudcost.adapter.notify;

import com.cloudcost.application.port.NotificationChannelGateway;
import com.cloudcost.domain.alert.DiscordWebhook;
import com.cloudcost.domain.alert.NotificationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Discord 웹훅 발송 (ADR-019). 보내기 직전에 주소를 다시 검사하고(discord.com/api/webhooks/만), 리다이렉트를 따라가지 않는다.
 * 메시지 안의 @everyone·@here 같은 멘션은 막는다 (allowed_mentions 비움). 로그에는 웹훅 주소를 남기지 않는다.
 */
@Component
class DiscordWebhookGateway implements NotificationChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookGateway.class);
    /** Discord 메시지 content 상한 */
    static final int MAX_CONTENT = 2000;

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
    public SendResult send(NotificationChannel channel, String text) {
        String url;
        try {
            url = DiscordWebhook.validate(channel.webhookUrl());
        } catch (RuntimeException e) {
            return SendResult.failure("Discord 웹훅 주소가 올바르지 않아요. 주소를 다시 등록해 주세요.", false);
        }
        String content = text.length() > MAX_CONTENT ? text.substring(0, MAX_CONTENT - 1) + "…" : text;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                    "content", content,
                    "allowed_mentions", Map.of("parse", new String[0])))))
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
