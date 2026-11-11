package com.cloudcost.domain.alert;

import com.cloudcost.domain.DomainValidationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discord 웹훅 주소 검사·가리기. 서버가 이 주소로 요청을 보내므로 discord.com/api/webhooks/ 형식만 받는다
 * (사용자가 넣은 임의 주소로 서버가 요청하지 않게 — SSRF 방지).
 * 토큰이 들어 있는 비밀 값이라 저장 뒤에는 가린 주소만 보여 준다.
 */
public final class DiscordWebhook {

    private static final Pattern URL =
        Pattern.compile("^https://discord\\.com/api/webhooks/(\\d{5,25})/([A-Za-z0-9_-]{20,120})$");

    private DiscordWebhook() {}

    /** @return 앞뒤 공백을 뺀 주소 */
    public static String validate(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (!URL.matcher(trimmed).matches()) {
            throw new DomainValidationException("INVALID_WEBHOOK_URL",
                "Discord 웹훅 주소를 확인해 주세요. https://discord.com/api/webhooks/ 로 시작해야 해요.");
        }
        return trimmed;
    }

    /** 예: https://discord.com/api/webhooks/…7890/•••• (웹훅 id 끝 4자리만 남긴다) */
    public static String mask(String url) {
        Matcher m = URL.matcher(url == null ? "" : url);
        if (!m.matches()) {
            return "••••";
        }
        String id = m.group(1);
        return "https://discord.com/api/webhooks/…" + id.substring(id.length() - 4) + "/••••";
    }
}
