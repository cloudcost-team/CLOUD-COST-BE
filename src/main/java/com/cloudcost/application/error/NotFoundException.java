package com.cloudcost.application.error;

/**
 * 없거나 다른 사용자의 리소스. 존재 여부를 숨기기 위해 두 경우를 구분하지 않는다 (404).
 */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
