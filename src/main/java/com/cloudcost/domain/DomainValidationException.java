package com.cloudcost.domain;

/**
 * 입력값이 도메인 규칙에 맞지 않을 때. API에서는 400으로 응답한다.
 */
public class DomainValidationException extends RuntimeException {

    private final String code;

    public DomainValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
