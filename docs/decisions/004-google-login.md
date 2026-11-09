# ADR-004: 로그인은 Google로 하고, CloudCost Insight가 자체 액세스 토큰(JWT)을 발급한다

- 상태: 승인됨 (2026-10-13, 사용자 확인). 이메일+비밀번호 자체 로그인은 이후 추가할 수 있다.

## 배경
X-Owner-Id 헤더는 누구나 다른 사람 id를 넣을 수 있어 로컬 전용이다. 체험판을 공개하려면 로그인이 필요하다.
문자·이메일 인증 없이 가입시키되 사용자가 의심하지 않아야 한다. 비밀번호를 우리가 저장하지 않으면 신뢰와 보안 모두 낫다.

## 결정
- 브라우저가 Google Identity Services로 ID 토큰을 받아 `POST /api/v1/auth/google`로 보낸다.
- 서버는 Google 공개키(JWKS)로 RS256 서명, iss(accounts.google.com), aud(우리 클라이언트 ID), exp, `email_verified=true`를 검증한다.
- 사용자는 (google, sub)로 식별하고 처음이면 자동 가입한다. 저장: Google sub, 이메일, 이름. 비밀번호·Google 데이터 권한은 없다.
- 서버가 HS256 JWT(1시간, iss/aud 고정, `JWT_SECRET` 32바이트 이상)를 발급하고, API는 `Authorization: Bearer`의 sub를 owner_id로 쓴다.
- AUTH_MODE=google(배포)에서는 토큰 필수. AUTH_MODE=dev(APP_ENV=local 전용)에서는 토큰이 없을 때만 X-Owner-Id를 허용한다.
- Spring Security는 쓰지 않는다. 인증 지점이 owner 해석(`OwnerArgumentResolver`) 하나라 그것으로 충분하다.

## 결과
- 배포 시 Google은 localhost 외에는 HTTPS 원본만 허용하므로 도메인 + HTTPS가 필요하다.
- Google 동의 화면이 테스트 모드면 등록한 테스트 사용자만 로그인된다. 공개할 때 프로덕션으로 바꾼다 (이메일·이름 스코프는 심사 불필요).
- 토큰 1시간 만료 후 다시 로그인한다 (→ ADR-017에서 리프레시 토큰 추가). 로그아웃은 클라이언트가 토큰을 버린다.
- Swagger 테스트는 /login.html에서 받은 토큰을 Authorize에 붙여 넣는다.
