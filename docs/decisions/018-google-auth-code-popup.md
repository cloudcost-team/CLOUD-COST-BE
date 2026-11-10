# ADR-018: Google 로그인을 인가 코드(OAuth 팝업) 방식으로도 받는다

- 상태: 결정 (2026-11-11, FE 요청). ADR-004를 확장한다. 기존 ID 토큰 방식은 그대로 둔다.

## 배경
로그인 화면의 Google 버튼을 우리 디자인 시스템 버튼으로 바꾸려 한다. Google 공식 버튼은 iframe이라 스타일을 못 바꾸고,
One Tap은 브라우저·계정 상태에 따라 창이 안 뜨는 일이 많다. `google.accounts.oauth2.initCodeClient`(`ux_mode: "popup"`)는
어떤 버튼에서도 열 수 있지만 FE에 ID 토큰이 아니라 인가 코드를 준다.

## 결정
- `POST /api/v1/auth/google`이 `{ "idToken" }` 또는 `{ "code" }` 중 하나를 받는다 (둘 다·둘 다 없음은 400).
- `code`면 서버가 `https://oauth2.googleapis.com/token`에 `grant_type=authorization_code`, `redirect_uri=postmessage`(팝업 방식 고정값),
  클라이언트 ID·시크릿으로 교환하고, 받은 `id_token`을 ADR-004와 같은 규칙(서명·iss·aud·exp·email_verified)으로 검증한다.
  TLS로 Google에서 직접 받았더라도 검증을 건너뛰지 않는다.
- 클라이언트 시크릿은 환경변수 `GOOGLE_CLIENT_SECRET`으로만 둔다. 없으면 `code` 방식만 503 `GOOGLE_LOGIN_NOT_CONFIGURED`.
- 교환·검증 실패는 새 코드 401 `INVALID_AUTH_CODE`로 알린다(만료·재사용·Google 거절 포함). 원인은 드러내지 않는다.
- 응답 `user.picture`에 ID 토큰의 `picture` 클레임을 담는다. 저장하지 않으므로 로그인 응답에만 있다.
- scope는 `openid email profile`만 쓴다. 받은 access_token은 쓰지 않고 버린다(Google 데이터 권한 없음).

## 결과
- 배포 서버 `.env`에 `GOOGLE_CLIENT_SECRET`을 추가해야 한다 (docs/DEPLOY.md §5).
- Google 콘솔의 승인된 JavaScript 원본은 지금과 같다. 팝업 방식은 리디렉션 URI 등록이 필요 없다.
- 서버가 Google 토큰 엔드포인트를 부르므로 로그인 1회에 외부 호출이 1번 늘어난다.
