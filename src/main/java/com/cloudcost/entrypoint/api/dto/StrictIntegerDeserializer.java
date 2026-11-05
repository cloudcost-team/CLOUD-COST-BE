package com.cloudcost.entrypoint.api.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/**
 * 정수만 받는다. Jackson 기본 설정은 20.9를 20으로 버려 범위 검사(@Max)를 지나가므로, 소수·문자열은 400으로 거절한다.
 * 공유 ObjectMapper 설정(ACCEPT_FLOAT_AS_INT)은 그대로 두고 필요한 필드에만 붙인다.
 */
public class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
        }
        return p.getIntValue();
    }
}
