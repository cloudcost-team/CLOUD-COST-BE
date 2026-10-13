package com.cloudcost.entrypoint.api;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.error.AwsAccessException;
import com.cloudcost.application.error.ConflictException;
import com.cloudcost.application.error.NotFoundException;
import com.cloudcost.domain.DomainValidationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 오류 형식: { "error": { "code", "message", "details" } }. AWS 원문·자격증명은 넣지 않는다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ErrorBody(Error error) {
        record Error(String code, String message, Map<String, Object> details) {
        }
    }

    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<ErrorBody> validation(DomainValidationException e) {
        return error(HttpStatus.BAD_REQUEST, e.code(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.code(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorBody> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, e.code(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ErrorBody> unauthorized(AuthenticationFailedException e) {
        HttpStatus status = "GOOGLE_LOGIN_NOT_CONFIGURED".equals(e.code())
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.UNAUTHORIZED;
        return error(status, e.code(), e.getMessage(), Map.of());
    }

    @ExceptionHandler(AwsAccessException.class)
    ResponseEntity<ErrorBody> aws(AwsAccessException e) {
        HttpStatus status;
        String code;
        switch (e.category()) {
            case ASSUME_ROLE_DENIED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "AWS_ASSUME_ROLE_FAILED";
            }
            case PERMISSION_DENIED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "AWS_PERMISSION_DENIED";
            }
            case BACKEND_CREDENTIALS_MISSING -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "BACKEND_AWS_NOT_CONFIGURED";
            }
            case THROTTLED -> {
                status = HttpStatus.TOO_MANY_REQUESTS;
                code = "AWS_THROTTLED";
            }
            case UNAVAILABLE -> {
                status = HttpStatus.BAD_GATEWAY;
                code = "AWS_UNAVAILABLE";
            }
            default -> {
                status = HttpStatus.BAD_GATEWAY;
                code = "AWS_ERROR";
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", e.action());
        details.put("awsErrorCode", e.awsErrorCode());
        log.info("AWS call failed: category={} action={} code={}", e.category(), e.action(), e.awsErrorCode());
        return error(status, code, e.getMessage(), details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "UUID".equals(e.getRequiredType() == null ? null : e.getRequiredType().getSimpleName())
            ? e.getName() + " 형식이 올바르지 않습니다 (UUID)."
            : e.getName() + " 형식이 올바르지 않습니다.";
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of("field", e.getName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값이 올바르지 않습니다.", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 형식이 올바르지 않습니다.", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorBody> noResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "경로를 찾을 수 없습니다.", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        String requestId = UUID.randomUUID().toString();
        log.error("Unhandled error requestId={} type={}", requestId, e.getClass().getName(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "내부 오류가 발생했습니다.", Map.of("requestId", requestId));
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message,
                                                   Map<String, Object> details) {
        return ResponseEntity.status(status).body(new ErrorBody(new ErrorBody.Error(code, message, details)));
    }
}
