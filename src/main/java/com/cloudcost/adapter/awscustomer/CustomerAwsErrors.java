package com.cloudcost.adapter.awscustomer;

import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.AwsAccessException.Category;
import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * AWS SDK 예외 → 분류 + 원인이 드러나는 한국어 메시지. AWS 원문 메시지(요청 ID, ARN 등 포함 가능)는 넘기지 않는다.
 */
final class CustomerAwsErrors {

    static final Set<String> PERMISSION_CODES = Set.of(
        "AccessDenied", "AccessDeniedException", "UnauthorizedOperation", "UnauthorizedException",
        "AuthorizationError", "Client.UnauthorizedOperation");
    static final Set<String> BACKEND_CREDENTIAL_CODES = Set.of(
        "InvalidClientTokenId", "SignatureDoesNotMatch", "UnrecognizedClientException", "MissingAuthenticationToken");
    /** 옵트인 리전이 꺼져 있을 때 EC2·CloudWatch가 돌려주는 코드 */
    static final Set<String> REGION_CODES = Set.of("OptInRequired", "InvalidClientTokenId", "UnrecognizedClientException");
    static final Set<String> THROTTLING_CODES = Set.of(
        "Throttling", "ThrottlingException", "RequestLimitExceeded", "TooManyRequestsException",
        "LimitExceededException");

    private CustomerAwsErrors() {
    }

    static AwsAccessException translate(Throwable error, String action) {
        AwsServiceException service = find(error, AwsServiceException.class);
        if (service != null) {
            return fromService(service, action);
        }
        SdkClientException client = find(error, SdkClientException.class);
        if (client != null && isMissingCredentials(client)) {
            return new AwsAccessException(Category.BACKEND_CREDENTIALS_MISSING, "NoCredentials", action,
                "서버의 AWS 자격증명이 설정되지 않았습니다 (관리자 설정 필요: docs/AWS_SETUP_GUIDE.md 1~2절). 사용자 설정 문제가 아닙니다.");
        }
        if (client != null) {
            return new AwsAccessException(Category.UNAVAILABLE, "ClientError", action,
                "AWS에 연결하지 못했습니다 (" + action + "). 네트워크 상태를 확인하고 잠시 후 다시 시도하세요.");
        }
        return new AwsAccessException(Category.UNKNOWN, "Unknown", action, "AWS 호출이 실패했습니다 (" + action + ").");
    }

    private static AwsAccessException fromService(AwsServiceException e, String action) {
        String code = e.awsErrorDetails() == null || e.awsErrorDetails().errorCode() == null
            ? "Unknown"
            : e.awsErrorDetails().errorCode();
        boolean assumeRole = action.startsWith("sts:");

        if (assumeRole && BACKEND_CREDENTIAL_CODES.contains(code)) {
            return new AwsAccessException(Category.BACKEND_CREDENTIALS_MISSING, code, action,
                "서버의 AWS 자격증명이 유효하지 않습니다 (관리자 설정 필요). 사용자 설정 문제가 아닙니다.");
        }
        if ("RegionDisabledException".equals(code)) {
            return new AwsAccessException(Category.REGION_DISABLED, code, action,
                "RegionDisabled: 연결한 리전이 AWS 계정에서 활성화되어 있지 않습니다 (" + action + "). "
                    + "AWS 콘솔 → 계정 → AWS 리전에서 리전을 활성화하거나 다른 리전으로 연결하세요.");
        }
        if (!assumeRole && REGION_CODES.contains(code)) {
            return new AwsAccessException(Category.REGION_DISABLED, code, action,
                code + ": 연결한 리전에서 요청이 거부되었습니다 (" + action + "). "
                    + "리전이 AWS 계정에서 활성화되어 있는지 확인하세요.");
        }
        if (assumeRole && (PERMISSION_CODES.contains(code) || e.statusCode() == 403)) {
            return new AwsAccessException(Category.ASSUME_ROLE_DENIED, code, action,
                "AccessDenied: Role을 맡을 수 없습니다. ① Role ARN 오타 ② Trust Policy의 sts:ExternalId가 이 연결의 External ID와 다름"
                    + " ③ Trust Policy의 Principal이 CloudCost Insight 백엔드 Role이 아님 ④ Role 이름이 CloudCost로 시작하지 않음"
                    + " ⑤ Role이 아직 생성 중인지 ⑥ CloudCost Insight 스택이나 Role이 삭제되지 않았는지 확인하세요.");
        }
        if ("AuthFailure".equals(code)) {
            return new AwsAccessException(Category.PERMISSION_DENIED, code, action,
                "AuthFailure: " + action + " 요청이 거부되었습니다. 연결한 리전이 계정에서 활성화되어 있는지 확인하세요.");
        }
        if (PERMISSION_CODES.contains(code)) {
            String label = code.contains("UnauthorizedOperation") ? "UnauthorizedOperation" : "AccessDenied";
            return new AwsAccessException(Category.PERMISSION_DENIED, code, action,
                label + ": " + action + " 권한이 없습니다. 고객 Role의 권한 정책을 확인하세요.");
        }
        if (THROTTLING_CODES.contains(code) || e.isThrottlingException()) {
            return new AwsAccessException(Category.THROTTLED, code, action,
                code + ": AWS 요청이 일시적으로 제한되었습니다 (" + action + "). 잠시 후 다시 시도하세요.");
        }
        if (e.statusCode() >= 500) {
            return new AwsAccessException(Category.UNAVAILABLE, code, action,
                "AWS 서비스가 일시적으로 응답하지 않습니다 (" + action + "). 잠시 후 다시 시도하세요.");
        }
        return new AwsAccessException(Category.UNKNOWN, code, action, code + ": AWS 호출이 실패했습니다 (" + action + ").");
    }

    private static boolean isMissingCredentials(SdkClientException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("Unable to load credentials") || message.contains(
            "Profile file contained no credentials")
            || message.contains("profile");
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
