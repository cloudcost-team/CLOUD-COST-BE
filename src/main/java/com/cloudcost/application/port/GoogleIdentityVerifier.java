package com.cloudcost.application.port;

import com.cloudcost.domain.user.VerifiedIdentity;

/**
 * Google 로그인 검증 (구현: adapter/auth). Google 공개키 서명, 발급처, 우리 클라이언트 ID(aud), 만료, email_verified를 확인한다.
 * 실패하면 AuthenticationFailedException.
 */
public interface GoogleIdentityVerifier {

    /** 브라우저가 Google에서 직접 받은 ID 토큰(credential)을 검증한다. 실패: INVALID_ID_TOKEN */
    VerifiedIdentity verify(String idToken);

    /**
     * OAuth 팝업(initCodeClient, redirect_uri=postmessage)이 준 인가 코드를 Google 토큰 엔드포인트에서 ID 토큰으로 바꾼 뒤
     * {@link #verify}와 같은 규칙으로 검증한다. 교환 실패: INVALID_AUTH_CODE
     */
    VerifiedIdentity verifyAuthCode(String code);
}
