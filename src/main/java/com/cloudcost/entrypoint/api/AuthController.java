package com.cloudcost.entrypoint.api;

import com.cloudcost.application.AuthService;
import com.cloudcost.application.AuthService.LoginResult;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.entrypoint.api.auth.CurrentOwner;
import com.cloudcost.entrypoint.config.CloudCostProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "0. 로그인", description = "Google로 로그인하고 CloudCost Insight 액세스 토큰(JWT)을 받는다. 비밀번호는 받지 않는다.")
class AuthController {

    private final AuthService auth;
    private final CloudCostProperties properties;

    AuthController(AuthService auth, CloudCostProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    record AuthConfigResponse(
            @Schema(description = "dev: 로컬 개발(헤더 허용) / google: 로그인 필수") String authMode,
            @Schema(description = "Google 로그인 버튼에 쓰는 OAuth 클라이언트 ID (공개 값)") String googleClientId) {}

    record GoogleLoginRequest(
            @NotBlank @Schema(description = "Google 로그인 버튼이 돌려준 ID 토큰(credential)") String idToken) {}

    record UserResponse(String id, String email, String name, String provider, Instant createdAt, Instant lastLoginAt) {
        static UserResponse of(AppUser u) {
            return new UserResponse(u.id(), u.email(), u.displayName(), u.provider(), u.createdAt(), u.lastLoginAt());
        }
    }

    record LoginResponse(
            @Schema(description = "API 호출 때 Authorization: Bearer <accessToken>") String accessToken,
            String tokenType,
            Instant expiresAt,
            @Schema(description = "이번 로그인으로 가입했는지") boolean newUser,
            UserResponse user) {}

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
            description = "Google ID 토큰의 서명·발급처·클라이언트 ID·만료·이메일 인증 여부를 검증하고 액세스 토큰을 발급한다. "
                    + "저장하는 것은 Google 고유 ID, 이메일, 이름뿐이다. 브라우저 테스트: /login.html")
    LoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        LoginResult result = auth.loginWithGoogle(request.idToken());
        return new LoginResponse(result.token().value(), "Bearer", result.token().expiresAt(), result.newUser(),
                UserResponse.of(result.user()));
    }

    @GetMapping("/api/v1/me")
    @Operation(summary = "내 정보")
    UserResponse me(@CurrentOwner String owner) {
        return UserResponse.of(auth.me(owner));
    }
}
