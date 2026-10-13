package com.cloudcost.application.error;

/**
 * 현재 상태에서 할 수 없는 요청 (409).
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
