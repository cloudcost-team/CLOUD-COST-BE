package com.cloudcost.fakes;

import com.cloudcost.application.error.AuthenticationFailedException;
import com.cloudcost.application.port.GoogleIdentityVerifier;
import com.cloudcost.domain.user.AppUser;
import com.cloudcost.domain.user.VerifiedIdentity;

/**
 * 실제 Google 대신 쓴다. "google:<sub>:<email>" 형식의 토큰만 유효한 것으로 본다.
 * 인가 코드는 "code:" + 그 토큰 형식만 유효하다 ({@link #code}).
 * 실제 서명 검증은 GoogleIdTokenVerifierTest가 따로 확인한다.
 */
public class FakeGoogleIdentityVerifier implements GoogleIdentityVerifier {

    public static String token(String subject, String email) {
        return "google:" + subject + ":" + email;
    }

    public static String code(String subject, String email) {
        return "code:" + token(subject, email);
    }

    public static String picture(String subject) {
        return "https://lh3.googleusercontent.com/a/" + subject;
    }

    @Override
    public VerifiedIdentity verifyAuthCode(String code) {
        if (!code.startsWith("code:")) {
            throw new AuthenticationFailedException("INVALID_AUTH_CODE", "Google 로그인을 마치지 못했습니다. 다시 로그인하세요.");
        }
        return verify(code.substring("code:".length()));
    }

    @Override
    public VerifiedIdentity verify(String idToken) {
        String[] parts = idToken.split(":", 3);
        if (parts.length != 3 || !parts[0].equals("google")) {
            throw new AuthenticationFailedException("INVALID_ID_TOKEN", "Google 로그인 정보를 확인하지 못했습니다. 다시 로그인하세요.");
        }
        return new VerifiedIdentity(AppUser.GOOGLE, parts[1], parts[2], "Tester " + parts[1], picture(parts[1]));
    }
}
