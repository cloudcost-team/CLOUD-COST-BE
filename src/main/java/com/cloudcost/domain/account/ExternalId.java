package com.cloudcost.domain.account;

import com.cloudcost.domain.DomainValidationException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 연결마다 서버가 발급하는 External ID (confused deputy 방지). 사용자가 정하거나 바꿀 수 없다.
 * 형식: "cci-" + hex 24자 (96비트).
 */
public record ExternalId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^cci-[0-9a-f]{24}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public ExternalId {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new DomainValidationException("INVALID_EXTERNAL_ID", "External ID 형식이 올바르지 않습니다.");
        }
    }

    public static ExternalId generate() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return new ExternalId("cci-" + HexFormat.of().formatHex(bytes));
    }

    /** 로그에 남기지 않도록 toString은 일부만 보여 준다. */
    @Override
    public String toString() {
        return "ExternalId(cci-" + value.substring(4, 8) + "…)";
    }
}
