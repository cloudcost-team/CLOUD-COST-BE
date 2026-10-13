package com.cloudcost.application.error;

/** 로그인 실패 또는 토큰이 없거나 유효하지 않음 (401). 원인 상세(어느 검사에서 실패했는지)는 응답에 넣지 않는다. */
public class AuthenticationFailedException extends RuntimeException {

    private final String code;

    public AuthenticationFailedException(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
