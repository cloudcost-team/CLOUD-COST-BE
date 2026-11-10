package com.cloudcost.entrypoint.api;

import com.cloudcost.application.AuthService;
import com.cloudcost.application.AuthService.LoginResult;
import com.cloudcost.application.AuthService.RefreshResult;
import com.cloudcost.application.port.AccessTokens.IssuedToken;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.config.CloudCostProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "0. 로그인", description = "Google로 로그인하고 CloudCost Insight 액세스 토큰(JWT)을 받는다. 비밀번호는 받지 않는다.")
class AuthController {

    /** 리프레시 토큰 쿠키. 자바스크립트로 읽을 수 없고(HttpOnly) /api/v1/auth 요청에만 실린다. */
    static final String REFRESH_COOKIE = "cci_refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final CloudCostProperties properties;

    AuthController(AuthService auth, CloudCostProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    record AuthConfigResponse(
            @Schema(description = "dev: 로컬 개발(헤더 허용) / google: 로그인 필수") String authMode,
            @Schema(description = "Google 로그인 버튼에 쓰는 OAuth 클라이언트 ID (공개 값)") String googleClientId) {}

    @Schema(description = "idToken과 code 중 하나만 보낸다")
    record GoogleLoginRequest(
            @Schema(description = "Google 로그인 버튼·One Tap이 돌려준 ID 토큰(credential)") String idToken,
            @Schema(description = "OAuth 팝업(initCodeClient, ux_mode=popup)이 돌려준 인가 코드") String code) {

        @AssertTrue(message = "idToken과 code 중 하나만 보내세요.")
        @Schema(hidden = true)
        boolean isExactlyOne() {
            return isBlank(idToken) != isBlank(code);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    record UserResponse(
            String id, String email, String name, String provider,
            @Schema(description = "Google 프로필 사진 URL. 로그인 응답에만 있고 /me에서는 null (저장하지 않는다)") String picture,
            Instant createdAt, Instant lastLoginAt) {
        static UserResponse of(AppUser u) {
            return of(u, null);
        }

        static UserResponse of(AppUser u, String picture) {
            return new UserResponse(
                    u.id(), u.email(), u.displayName(), u.provider(), picture, u.createdAt(), u.lastLoginAt());
        }
    }

    record LoginResponse(
            @Schema(description = "API 호출 때 Authorization: Bearer <accessToken>") String accessToken,
            String tokenType,
            Instant expiresAt,
            @Schema(description = "이번 로그인으로 가입했는지") boolean newUser,
            UserResponse user) {}

    record TokenResponse(
            @Schema(description = "API 호출 때 Authorization: Bearer <accessToken>") String accessToken,
            String tokenType,
            Instant expiresAt) {}

    @GetMapping("/api/v1/auth/config")
    @SecurityRequirements
    @Operation(summary = "로그인 설정 (로그인 화면용, 인증 불필요)")
    AuthConfigResponse config() {
        String clientId = properties.auth().googleClientId();
        return new AuthConfigResponse(
                properties.auth().devMode() ? "dev" : "google", clientId == null ? "" : clientId.trim());
    }

    @PostMapping("/api/v1/auth/google")
    @SecurityRequirements
    @Operation(
            summary = "Google로 로그인 (처음이면 자동 가입)",
            description = "idToken(로그인 버튼·One Tap) 또는 code(OAuth 팝업 인가 코드, redirect_uri=postmessage) 중 하나를 받는다. "
                    + "code면 서버가 Google 토큰 엔드포인트에서 ID 토큰으로 바꾼다. "
                    + "Google ID 토큰의 서명·발급처·클라이언트 ID·만료·이메일 인증 여부를 검증하고 액세스 토큰을 발급한다. "
                    + "저장하는 것은 Google 고유 ID, 이메일, 이름뿐이다(사진 URL은 응답에만). 브라우저 테스트: /login.html. "
                    + "리프레시 토큰은 본문이 아니라 HttpOnly 쿠키(cci_refresh)로 내려준다.")
    LoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request, HttpServletResponse response) {
        LoginResult result = request.code() != null && !request.code().isBlank()
                ? auth.loginWithGoogleCode(request.code())
                : auth.loginWithGoogle(request.idToken());
        setRefreshCookie(response, result.refreshToken());
        return new LoginResponse(result.token().value(), "Bearer", result.token().expiresAt(), result.newUser(),
                UserResponse.of(result.user(), result.picture()));
    }

    @PostMapping("/api/v1/auth/refresh")
    @SecurityRequirements
    @Operation(
            summary = "액세스 토큰 다시 받기",
            description = "cci_refresh 쿠키로 새 액세스 토큰을 발급하고 쿠키도 새 값으로 바꾼다(한 번 쓴 쿠키는 다시 쓸 수 없다). "
                    + "브라우저는 credentials: 'include'로 호출해야 쿠키가 실린다. 쿠키가 없거나 만료되면 401 → 다시 로그인.")
    TokenResponse refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        RefreshResult result;
        try {
            result = auth.refresh(refreshToken);
        } catch (RuntimeException e) {
            clearRefreshCookie(response);
            throw e;
        }
        setRefreshCookie(response, result.refreshToken());
        return new TokenResponse(result.token().value(), "Bearer", result.token().expiresAt());
    }

    @PostMapping("/api/v1/auth/logout")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "로그아웃", description = "이 로그인의 리프레시 토큰을 무효화하고 쿠키를 지운다. 액세스 토큰은 클라이언트가 버린다.")
    void logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
        auth.logout(refreshToken);
        clearRefreshCookie(response);
    }

    private void setRefreshCookie(HttpServletResponse response, IssuedToken token) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(token.value(), properties.auth().refreshTokenTtl()));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO));
    }

    private String refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.auth().refreshCookieSecure())
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    @GetMapping("/api/v1/me")
    @Operation(summary = "내 정보")
    UserResponse me(@CurrentOwner String owner) {
        return UserResponse.of(auth.me(owner));
    }
}
