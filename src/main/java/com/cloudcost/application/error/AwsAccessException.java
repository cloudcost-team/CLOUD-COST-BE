package com.cloudcost.application.error;

/**
 * AWS 호출 실패를 분류한 예외. 어댑터가 SDK 예외를 이것으로 바꿔 던진다.
 *
 * <p>메시지는 원인이 드러나는 한국어로 쓰고, AWS 원문 메시지·자격증명·요청 ID는 넣지 않는다.
 */
public class AwsAccessException extends RuntimeException {

    public enum Category {
        /**
         * 고객 Role을 맡지 못함 (ARN 오타, External ID 불일치, Trust Policy Principal 오류)
         */
        ASSUME_ROLE_DENIED,
        /**
         * 고객 Role에 필요한 권한이 없음
         */
        PERMISSION_DENIED,
        /**
         * 연결 리전이 고객 계정에서 활성화되지 않음 (옵트인 리전)
         */
        REGION_DISABLED,
        /**
         * 우리 서버의 AWS 자격증명이 설정되지 않음 (사용자 잘못이 아님)
         */
        BACKEND_CREDENTIALS_MISSING,
        /**
         * 요청 빈도 제한
         */
        THROTTLED,
        /**
         * 네트워크·AWS 장애
         */
        UNAVAILABLE,
        UNKNOWN
    }

    private final Category category;
    private final String awsErrorCode;
    private final String action;

    public AwsAccessException(Category category, String awsErrorCode, String action, String message) {
        super(message, null, false, false);
        this.category = category;
        this.awsErrorCode = awsErrorCode;
        this.action = action;
    }

    public Category category() {
        return category;
    }

    public String awsErrorCode() {
        return awsErrorCode;
    }

    public String action() {
        return action;
    }

    /**
     * 사용자가 고객 계정에서 고쳐야 하는 오류인지 (연결을 ERROR로 바꾼다). 나머지는 다시 시도하면 풀릴 수 있다.
     */
    public boolean breaksConnection() {
        return category == Category.ASSUME_ROLE_DENIED
            || category == Category.PERMISSION_DENIED
            || category == Category.REGION_DISABLED;
    }

    /**
     * 잠시 후 같은 요청을 다시 보내면 성공할 수 있는지 (요청 제한·일시 장애).
     */
    public boolean retryable() {
        return category == Category.THROTTLED || category == Category.UNAVAILABLE;
    }

    /**
     * DB·응답에 남길 분류 코드 (예: PERMISSION_DENIED)
     */
    public String code() {
        return category.name();
    }
}
