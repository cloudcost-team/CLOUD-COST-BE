package com.cloudcost.application.port;

import com.cloudcost.domain.user.VerifiedIdentity;

/**
 * Google ID 토큰 검증 (구현: adapter/auth). Google 공개키 서명, 발급처, 우리 클라이언트 ID(aud), 만료, email_verified를 확인한다.
 * 실패하면 AuthenticationFailedException.
 */
public interface GoogleIdentityVerifier {

    VerifiedIdentity verify(String idToken);
}
