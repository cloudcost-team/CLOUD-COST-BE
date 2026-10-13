package com.cloudcost.entrypoint.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/** 로그 메시지 마스킹: AWS 액세스 키 ID, 임시 자격증명 필드, JWT, Authorization/Bearer 값. */
public class MaskingMessageConverter extends MessageConverter {

    private static final Pattern ACCESS_KEY_ID = Pattern.compile("(AKIA|ASIA)[A-Z0-9]{16}");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");
    private static final Pattern SECRET_FIELDS = Pattern.compile(
            "(?i)((?:SessionToken|SecretAccessKey|AccessKeyId|Authorization|X-Amz-Security-Token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]+");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");

    @Override
    public String convert(ILoggingEvent event) {
        return mask(super.convert(event));
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String masked = ACCESS_KEY_ID.matcher(message).replaceAll("***");
        masked = JWT.matcher(masked).replaceAll("***");
        masked = SECRET_FIELDS.matcher(masked).replaceAll("$1***");
        return BEARER.matcher(masked).replaceAll("$1***");
    }
}
