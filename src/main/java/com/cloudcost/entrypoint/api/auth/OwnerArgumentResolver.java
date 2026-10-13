package com.cloudcost.entrypoint.api.auth;

import com.cloudcost.application.AuthService;
import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.entrypoint.config.CloudCostProperties;
import java.util.regex.Pattern;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 요청한 사용자(owner_id)를 정한다.
 * <ol>
 *   <li>{@code Authorization: Bearer <JWT>}가 있으면 검증해 그 사용자 ID를 쓴다 (Google 로그인 후 발급한 토큰).
 *   <li>없을 때 AUTH_MODE=dev(로컬 전용)면 X-Owner-Id 헤더 → 없으면 cci.auth.dev-default-owner.
 *   <li>AUTH_MODE=google이면 401. X-Owner-Id는 무시한다.
 * </ol>
 */
@Component
public class OwnerArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Owner-Id";
    private static final String BEARER = "Bearer ";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._@:-]{1,64}$");

    private final AuthService auth;
    private final boolean devMode;
    private final String defaultOwner;

    OwnerArgumentResolver(AuthService auth, CloudCostProperties properties) {
        this.auth = auth;
        this.devMode = properties.auth().devMode();
        this.defaultOwner = properties.auth().devDefaultOwner();
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentOwner.class) && parameter.getParameterType() == String.class;
    }

    @Override
    public String resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
        String authorization = webRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
                throw new AuthenticationFailedException("INVALID_TOKEN", "Authorization 헤더는 'Bearer <토큰>' 형식이어야 합니다.");
            }
            return auth.authenticate(authorization.substring(BEARER.length()).trim());
        }
        if (!devMode) {
            throw new AuthenticationFailedException("LOGIN_REQUIRED", "로그인이 필요합니다.");
        }
        String header = webRequest.getHeader(HEADER);
        String owner = header == null || header.isBlank() ? defaultOwner : header.trim();
        if (!VALID.matcher(owner).matches()) {
            throw new AuthenticationFailedException("UNAUTHORIZED", "X-Owner-Id 형식이 올바르지 않습니다 (영문·숫자·._@:- 1~64자).");
        }
        return owner;
    }
}
