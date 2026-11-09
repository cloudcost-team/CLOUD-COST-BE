# ADR-017: 리프레시 토큰을 HttpOnly 쿠키로 발급하고 쓸 때마다 바꾼다

- 상태: 결정 (2026-11-10, 사용자 확인). ADR-004의 "리프레시 토큰은 백로그"를 대체한다.

## 배경
액세스 토큰(JWT)이 1시간이라 1시간마다 Google 로그인을 다시 해야 했다.
프론트는 `https://www.cloud-cost.cloud`, API는 `https://api.cloud-cost.cloud`로 같은 사이트(cloud-cost.cloud)의 서브도메인이다.

## 결정
- `POST /auth/google` 성공 시 액세스 토큰은 지금처럼 본문으로, 리프레시 토큰은 쿠키로만 준다.
  `cci_refresh`: `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth; Max-Age=14일`. Domain을 지정하지 않는다(api 호스트 전용).
- 리프레시 토큰은 32바이트 난수(base64url). DB(`refresh_tokens`)에는 SHA-256 해시만 둔다.
- `POST /auth/refresh`: 쿠키 → 새 액세스 토큰 + 새 쿠키(rotation). 쓴 토큰은 `used_at`을 조건부 UPDATE로 기록해 한 번만 쓸 수 있다.
  이미 쓴 토큰이 다시 오면 탈취로 보고 같은 로그인(family) 토큰을 모두 무효화한다(401 `REFRESH_TOKEN_REUSED`).
- `POST /auth/logout`: family 무효화 + 쿠키 삭제(204). 액세스 토큰은 만료까지 유효하므로 클라이언트가 버린다.
- CORS는 등록된 출처에 대해 `Access-Control-Allow-Credentials: true`.
- CSRF: SameSite=Lax라 다른 사이트의 POST에는 쿠키가 실리지 않고, refresh 응답은 CORS로 다른 출처가 읽을 수 없다.

## 결과
- 프론트: 액세스 토큰은 메모리에만 둔다. 401이면 `fetch('/api/v1/auth/refresh', { method: 'POST', credentials: 'include' })` 후 재시도,
  새로고침 직후에도 refresh로 로그인 상태를 복구한다. 여러 요청이 동시에 401을 받아도 refresh는 한 번만 보낸다
  (같은 쿠키로 두 번 보내면 재사용으로 판정돼 로그아웃된다).
- `http://localhost:3000`에서 운영 API를 부르면 다른 사이트라 Lax 쿠키가 실리지 않는다. 로컬 개발은 로컬 백엔드나 개발 서버 프록시를 쓴다.
  https가 아닌 로컬 백엔드에서 Safari로 테스트할 때만 `REFRESH_COOKIE_SECURE=false`.
- 만료된 행은 그 사용자가 다시 로그인할 때 지운다.
