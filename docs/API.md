# API — 엔드포인트 계약

> Base: `/api/v1`. Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`. 이 문서가 기준이다.
> 사용자 구분: `Authorization: Bearer <accessToken>` (Google 로그인, 0절). 로컬(AUTH_MODE=dev)에서는 토큰이 없으면 `X-Owner-Id` 헤더 (없으면 `DEV_OWNER_ID`).
> 남의 리소스는 **404**다. 시각은 ISO-8601 UTC, 날짜는 Asia/Seoul 기준 `YYYY-MM-DD`.

## 0. 로그인 (ADR-004)
| 엔드포인트 | 인증 | 설명 |
|---|---|---|
| `GET /auth/config` | 없음 | `{ authMode: dev\|google, googleClientId }` |
| `POST /auth/google` | 없음 | 요청 `{ "idToken": "<Google credential>" }` 또는 `{ "code": "<authorization code>" }` (둘 중 하나만) → `{ accessToken, tokenType: "Bearer", expiresAt, newUser, user: { id, email, name, provider, picture } }`. 처음이면 자동 가입 |
| `POST /auth/refresh` | `cci_refresh` 쿠키 | → `{ accessToken, tokenType: "Bearer", expiresAt }` + 새 쿠키. 한 번 쓴 쿠키는 재사용 불가 (ADR-017) |
| `POST /auth/logout` | `cci_refresh` 쿠키 | 204. 리프레시 토큰 무효화 + 쿠키 삭제 |
| `GET /me` | 필요 | 내 정보 |

- `code` 방식 (ADR-018): FE가 `google.accounts.oauth2.initCodeClient({ ux_mode: "popup", scope: "openid email profile" })`로 받은 인가 코드를 보낸다. 서버가 Google 토큰 엔드포인트에서 `redirect_uri=postmessage`로 ID 토큰과 바꾼 뒤 `idToken` 방식과 같은 규칙으로 검증한다. `idToken` 방식도 계속 받는다.
- `user.picture`: Google 프로필 사진 URL (없으면 `null`). 저장하지 않으므로 로그인 응답에만 있고 `GET /me`에서는 `null`이다.
- 이후 모든 API: `Authorization: Bearer <accessToken>` (1시간). AUTH_MODE=dev(로컬)에서는 토큰이 없을 때만 X-Owner-Id 허용.
- 리프레시 토큰은 본문이 아니라 HttpOnly 쿠키 `cci_refresh`(Path=/api/v1/auth, SameSite=Lax, 14일)로만 온다. 브라우저는 login·refresh·logout을 `credentials: 'include'`로 호출한다.
- 브라우저 테스트 페이지: `/login.html` → 토큰 복사 → Swagger Authorize의 `bearerAuth`.
- 401 코드: `LOGIN_REQUIRED`(토큰 없음), `INVALID_TOKEN`(만료·위조), `INVALID_ID_TOKEN`(Google 검증 실패), `INVALID_AUTH_CODE`(인가 코드 교환 실패·만료·재사용, 교환한 토큰 검증 실패), `INVALID_REFRESH_TOKEN`(쿠키 없음·만료·무효), `REFRESH_TOKEN_REUSED`(이미 쓴 쿠키 → 그 로그인 전체 무효화). 503 `GOOGLE_LOGIN_NOT_CONFIGURED`(`GOOGLE_CLIENT_ID` 없음, `code` 방식은 `GOOGLE_CLIENT_SECRET`도 필요). 400 `VALIDATION_ERROR`: `idToken`·`code`가 둘 다 없거나 둘 다 있음.

## 오류 형식
```json
{ "error": { "code": "AWS_PERMISSION_DENIED", "message": "UnauthorizedOperation: ec2:DescribeInstances 권한이 없습니다. …", "details": { "action": "ec2:DescribeInstances", "awsErrorCode": "UnauthorizedOperation" } } }
```
| HTTP | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `INVALID_ROLE_ARN`, `INVALID_REGION`, `INVALID_PERIOD`, `INVALID_ALIAS` | 형식 오류 (accountId가 UUID가 아님 포함) |
| 401 | `UNAUTHORIZED` | X-Owner-Id 형식 오류 |
| 404 | `ACCOUNT_NOT_FOUND` | 없거나 남의 연결 |
| 409 | `ACCOUNT_NOT_ACTIVE`, `ACCOUNT_DISCONNECTED`, `ACCOUNT_ALREADY_CONNECTED`, `ROLE_ACCOUNT_CHANGED` | 상태 충돌 |
| 422 | `AWS_ASSUME_ROLE_FAILED`, `AWS_PERMISSION_DENIED`, `AWS_REGION_DISABLED` | 고객 Role·계정 설정 문제 (연결은 `ERROR`) |
| 429 | `AWS_THROTTLED` | 백오프로 3회 시도해도 요청 제한 |
| 502 | `AWS_UNAVAILABLE`, `AWS_ERROR` | AWS 연결 실패 |
| 503 | `BACKEND_AWS_NOT_CONFIGURED` | 서버 AWS 자격증명 없음 (사용자 잘못 아님) |

## 1. AWS 연결 (ADR-003, 예비: ADR-001)
### `GET /aws/regions` — 연결할 때 고를 수 있는 리전
```json
{ "regions": [ { "code": "ap-northeast-2", "label": "서울", "isDefault": true },
               { "code": "ap-northeast-1", "label": "도쿄", "isDefault": false }, … ] }
```
- 화면에 보일 순서대로 준다. 첫 번째(`ap-northeast-2`)가 기본값이다. 고른 `code`를 아래 `POST /aws/accounts`의 `region`으로 보낸다.
- 계정마다 따로 켜야 하는 옵트인 리전(홍콩·자카르타 등)은 넣지 않는다. AWS를 호출하지 않는다.
- 목록: 서울, 도쿄, 오사카, 싱가포르, 시드니, 뭄바이, 버지니아 북부, 오하이오, 캘리포니아 북부, 오레곤, 캐나다 중부, 프랑크푸르트, 아일랜드, 런던, 파리, 스톡홀름, 상파울루.

### `POST /aws/accounts` → 201
요청(선택): `{ "region": "ap-northeast-2", "alias": "개인 계정" }` — `alias`는 연결 이름(앞뒤 공백 제거, 40자 이하, 줄바꿈·제어 문자 불가, 비우면 null)
```json
{ "account": { "accountId": "…", "alias": "개인 계정", "status": "PENDING", "region": "ap-northeast-2", "regionLabel": "서울",
               "externalId": "cci-0123456789abcdef01234567", "roleArn": null, "awsAccountId": null, "lastError": null, "verifiedAt": null,
               "stackId": null, "stackName": "CloudCostInsight-1a2b3c4d", "disconnectedAt": null, … },
  "roleSetup": { "quickCreateUrl": "https://ap-northeast-2.console.aws.amazon.com/cloudformation/home?region=ap-northeast-2#/stacks/quickcreate?templateURL=…&stackName=CloudCostInsight-1a2b3c4d&param_ExternalId=cci-…&param_ConnectionId=…",
                 "requiredActions": ["ec2:DescribeInstances", "cloudwatch:GetMetricStatistics"],
                 "stackParameters": { "ExternalId": "cci-…", "TrustedPrincipalArn": "…", "ConnectionId": "…", "CallbackTopicArn": "…", "MonitoredRegion": "ap-northeast-2", "RoleName": "CloudCostReadOnlyRole-1a2b3c4d" },
                 "trustPolicy": { "Version": "2012-10-17", "Statement": [ { "Principal": { "AWS": "<CCI_BACKEND_ROLE_ARN>" },
                                  "Action": "sts:AssumeRole", "Condition": { "StringEquals": { "sts:ExternalId": "cci-…" } } } ] },
                 "permissionPolicy": { … "Condition": { "StringEquals": { "aws:RequestedRegion": ["ap-northeast-2"] } } },
                 "steps": ["1. …"], "warnings": [] } }
```
사용자가 `quickCreateUrl`로 스택을 만들면 서버가 SNS→SQS 신호를 받아 검증하고 `status`가 `ACTIVE`가 된다 (1~2분).
스택을 지우면 `DISCONNECTED`. 서버 설정이 없으면 `quickCreateUrl: null` + `warnings`.

**연결이 실패했을 때** (스택 신호를 받은 뒤 서버가 거절하거나 검증에 실패하면): 연결은 `ERROR`, `lastError`에 이유가 남고, 고객 스택은 롤백된다.
연결 대기 화면은 `status`를 확인하다가 `ERROR`가 되면 바로 `lastError`로 이유를 보여 준다.

| `lastError.code` | 뜻 |
|---|---|
| `ALREADY_CONNECTED` | 같은 사용자가 이 AWS 계정·리전을 이미 연결해 두었다 (기존 연결로 안내) |
| `ACCOUNT_MISMATCH` | Role이 스택과 다른 AWS 계정에 있거나, 이 연결이 이미 다른 AWS 계정에 연결되어 있다 |
| `INVALID_ROLE_ARN` | 신호의 Role ARN 형식이 올바르지 않다 |
| `ASSUME_ROLE_DENIED` 등 AWS 오류 분류 | 재시도(최대 6번, 10초 간격)를 마치고도 AssumeRole 검증에 실패했다. 재시도 중에는 `PENDING`을 유지한다 |

- 이미 검증된 연결은 거절되는 신호를 받아도 상태를 바꾸지 않는다 (신호는 누구나 보낼 수 있다).
- 스택이 우리 신호 전에 AWS에서 실패하면(Role 이름 중복, IAM 권한 없음 등) 서버는 알 수 없어 `PENDING`으로 남는다.
  화면은 2분이 지나면 `stackName` 스택의 CloudFormation **이벤트** 탭에서 상태 사유를 확인하라고 안내하고, 10분이 지나면 실패 화면으로 바꾼다.

### `GET /aws/cloudformation-template`
고객용 템플릿 YAML (관리자가 S3에 올리는 원본).

### `PUT /aws/accounts/{accountId}/role` → 200 (예비 경로)
요청: `{ "roleArn": "arn:aws:iam::123456789012:role/CloudCostReadOnlyRole" }`
실제 AssumeRole + DescribeInstances 1건. 성공하면 `status: ACTIVE`. 실패하면 422/502/503 + 연결은 `ERROR`.
### `GET /aws/accounts?includePending=false` · `GET /aws/accounts/{accountId}`
목록 / 상세(+ `roleSetup`). 목록 항목은 상세의 `account`와 같은 모양이다.
- 목록은 기본으로 **시작한 지 1시간이 지나도록 `PENDING`인 연결**(링크만 받고 스택을 만들지 않은 것)을 뺀다. `includePending=true`면 모두 보여 준다.
  상세 조회와 삭제는 숨겨진 연결에도 그대로 된다.
- `alias`: 연결 이름 (없으면 null → 화면에서는 "이름 없는 연결").
- `regionLabel`: 한국어 리전 이름 (`GET /aws/regions`의 `label`과 같다. 예: `ap-northeast-2`→서울). 목록에 없는 리전은 코드 그대로.
- `stackName`: 고객 계정에 만들어지는 스택 이름 (`CloudCostInsight-` + 연결 id 앞 8자리).

연결 `status`: `PENDING`(Role 전) / `ACTIVE` / `ERROR`(고쳐야 하는 오류, `lastError`에 한국어 원인) / `DISCONNECTED`(스택 삭제).
일시적인 AWS 오류(요청 제한·장애)는 `ACTIVE`를 유지하고 `lastError`만 채운다. 수집이 모두 성공하면 `lastError: null`.
검증된 뒤 `ERROR`가 된 연결은 1시간에 한 번 자동으로 다시 확인해 성공하면 `ACTIVE`로 돌아온다 (바로 확인: `PUT .../role`).
### `PATCH /aws/accounts/{accountId}` → 200 (연결 이름 변경)
요청: `{ "alias": "캡스톤 팀 계정" }` (빈 문자열·null이면 이름을 지운다). 응답: 연결(`account`와 같은 모양). AWS에는 아무것도 보내지 않는다.
400 `INVALID_ALIAS`(40자 초과·제어 문자), 404 `ACCOUNT_NOT_FOUND`(없거나 남의 연결).

### `DELETE /aws/accounts/{accountId}` → 200 (연결 취소 · 삭제)
연결과 이 연결로 모은 `ec2_instances`·`ec2_usage_hourly`·`sync_jobs`를 **본인 것만** 한 트랜잭션으로 지운다. 남의 연결은 404이고 아무것도 지우지 않는다.
고객 AWS 계정은 건드리지 않는다 (READ ONLY). 다시 연결하려면 연결을 새로 시작한다.
```json
{ "accountId": "…", "previousStatus": "ACTIVE", "stackName": "CloudCostInsight-1a2b3c4d", "awsCleanupNeeded": true,
  "message": "삭제했습니다. AWS 쪽도 끊으려면 AWS 콘솔 → CloudFormation에서 'CloudCostInsight-1a2b3c4d' 스택을 삭제하세요." }
```
- `awsCleanupNeeded`: 한 번이라도 검증됐고 스택 삭제 신호를 받지 않은 연결(`ACTIVE`, 검증된 뒤의 `ERROR`)이면 true. 화면은 `message`를 그대로 보여 준다.
- 삭제 후 그 스택에서 오는 신호: Delete는 무시하고 SUCCESS로 응답해 고객의 스택 삭제를 막지 않는다.
  Create/Update는 연결을 찾지 못해 FAILED로 응답하므로 고객 스택이 롤백되어 Role이 남지 않는다.

### `GET /aws/accounts/{accountId}/sync-jobs?limit=20`
`[ { jobId, trigger, status, instancesRefreshed, usageRefreshed, ec2RequestCount, cwRequestCount, instanceCount, usageRows, error, warning, startedAt, finishedAt } ]`
- `status`: `SUCCEEDED` / `PARTIAL`(일부만 저장. `error`에 첫 실패 원인, 실패한 구간은 다음 수집에서 다시 읽음) / `FAILED`(저장 없음).
- `cwRequestCount`는 재시도를 포함한 실제 호출 수다. 낭비 탐지용 네트워크 조회(인스턴스당 수집마다 2건, ADR-009)도 포함한다.

## 2. EC2 사용량·추정 비용 (ADR-002, 통화는 ADR-005)
모든 조회에 `currency=KRW|USD` (기본 **KRW**). 원화는 응답의 `exchangeRate`로 환산한 추정치다.
```json
"currency": "KRW",
"exchangeRate": { "base": "USD", "quote": "KRW", "rate": 1333.09, "date": "2026-10-12", "source": "유럽중앙은행 기준환율" }
```
- `currency=USD`면 `exchangeRate: null`. 지원하지 않는 통화는 400 `VALIDATION_ERROR`.
- 반올림: KRW 금액 원 단위·시간당 단가 0.1원 / USD 금액 센트·단가 소수 넷째 자리. 사용 시간(`usageHours`)은 통화와 무관.
- 환율을 못 받으면 마지막 환율 + `notes` 안내, 한 번도 없으면 USD로 응답.

모든 응답에 `dataSource: "aws"`, `costBasis: "estimated_on_demand"`, `currency`(요청한 통화, 기본 `KRW`), `freshness`, `notes`가 있다.
```json
"freshness": { "instancesRefreshedAt": "…", "instancesFromCache": true,
               "usageRefreshedAt": "2026-10-08T03:00:00Z", "usageFromCache": true,
               "nextUsageRefreshAt": "2026-10-08T04:00:00Z", "warnings": [], "warningDetails": [] }
```
`warningDetails`는 `warnings`와 같은 순서의 `{ "code", "message" }`다. 문구는 바뀔 수 있으니 화면은 `code`로 상태를 나눈다:
`PARTIAL_COLLECTION`(CloudWatch 호출 상한으로 일부만 수집, 다음 수집 때 이어서) · `AWS_THROTTLED`(AWS 요청 제한) ·
`USAGE_COLLECTION_FAILED`(그 밖의 이유로 사용량 일부 실패) · `COLLECTION_FAILED`(수집 전체 실패, 저장된 데이터 표시) ·
`CONNECTION_ERROR`(연결 ERROR 상태) · `PRICE_UNAVAILABLE`(단가 조회 실패).
`ACTIVE`이거나 검증된 뒤 `ERROR`가 된 연결이면 조회할 수 있다 (ERROR면 저장된 데이터 + `freshness.warnings`에 원인). 그 밖은 409.
일부 수집이 실패하면 받은 부분은 저장되고 `freshness.warnings`에 안내가 붙는다.

### `GET /aws/accounts/{accountId}/instances`
```json
{ "instances": [ { "instanceId": "i-…", "name": "dev-api", "instanceType": "t3.medium", "region": "ap-northeast-2",
                   "availabilityZone": "ap-northeast-2a", "state": "running", "launchTime": "…", "platformDetails": "Linux/UNIX",
                   "tenancy": "default", "spot": false, "present": true,
                   "currentRun": { "since": "…", "hours": 27.00, "note": "현재 실행 구간 기준입니다…" },
                   "estimatedHourlyCost": 0.0416, "note": null } ] }
```
### `GET /aws/accounts/{accountId}/costs?from=&to=` (기본: 이번 달 1일 ~ 오늘)
```json
{ "from": "2026-10-01", "to": "2026-10-08", "usageHours": 13.17, "estimatedCost": 0.55,
  "instances": [ { "instanceId": "i-…", "name": "…", "instanceType": "t3.medium", "state": "running",
                   "usageHours": 10.17, "estimatedHourlyCost": 0.0416, "estimatedCost": 0.42, "note": null } ] }
```
### `GET /aws/accounts/{accountId}/costs/daily?from=&to=` (기본: 최근 30일)
`days: [ { "date": "2026-10-07", "usageHours": 10.00, "estimatedCost": 0.42, "instances": [ … ], "spike": null } ]` — 사용이 없는 날도 포함.
- **급증일**(`spike`, 이슈 #29, ADR-011 · S06 빨간 막대 · S06-6 그날 상세). 급증이 아니거나 판정하지 않은 날은 `null`.
```json
"spike": { "ratio": 2.4, "baseline": 4100, "baselineDays": 30, "causeInstanceId": "i-0abc", "causeName": "web-server-1",
           "causeUsageHours": 24.00, "causeBaselineHours": 8.00,
           "message": "10/12 비용 급증 · 평소의 2.4배 · web-server-1 사용 시간이 늘었어요" }
```
  - 평소(`baseline`) = 그날 **직전 30일** 중 기록 있는 날(기록 시작일 `availableFrom` 이후, 처음 사용한 날부터)의 하루 추정 비용 **중앙값**. 쓴 기록은 있는데 단가를 하나도 모르는 날은 0원이 아니라 평소·판정 대상에서 뺀다. 조회 기간 앞 30일도 함께 읽어 첫날부터 판정한다.
  - 급증 = 그날 비용 ≥ 평소 × 1.5. 평소를 잰 날이 7일 미만이거나 평소가 0이면 판정하지 않는다. `ratio`는 소수 첫째 자리.
  - 원인 서버 = 단가를 아는 서버(비용에 들어간 서버) 중에서 그날 사용 시간이 자기 평소(평소 비용을 잰 날들의 하루 사용 시간 중앙값. 단가를 하나도 모르는 날은 단가를 아는 서버가 모두 0시간이라 넣지 않는다)보다 가장 많이 늘어난 인스턴스. 늘어난 서버가 없으면 `cause*`가 `null`이고 문구에 서버를 쓰지 않는다.
  - 진행 중인 오늘도 판정한다 (이미 기준을 넘었으면 급증). 단가를 모르는 서버는 비용에 들어가지 않는다.
### `GET /aws/accounts/{accountId}/costs/monthly?from=YYYY-MM&to=YYYY-MM` (기본: 최근 3개월)
`months: [ { "month": "2026-10", "usageHours": …, "estimatedCost": …, "estimatedCostChange": -1200, "instances": [ … ] } ]` — 일별 합산.
- `estimatedCostChange`: 직전 달 대비 추정 비용 증감 (요청 통화 기준, 반올림한 금액끼리의 차). 첫 달이면 `null`.
- 화면(계정 상세)은 최근 12개월을 한 번에 받아 원하는 달을 고르고(이전/다음 달, 월별 추이), 고른 달의 `instances`로 서버별 금액을 보여 준다.

**`availableFrom`** (`costs` · `costs/daily` · `costs/monthly` 공통): 이 날짜부터 사용량 기록이 있다 = 가장 먼저 본 인스턴스를 처음 수집할 때 소급한 시작일
(처음 본 시각 − `CCI_USAGE_BACKFILL`). 그보다 앞의 기간은 조회해도 0이고 `notes`에 안내가 붙는다. 아직 인스턴스를 본 적이 없으면 `null`.
화면은 이 날짜보다 앞선 달을 보여 주지 않는다 (0원을 "안 썼다"로 읽지 않게).

**단가를 찾지 못한 인스턴스**: `estimatedCost: null`, `note: "해당 인스턴스의 온디맨드 단가를 조회할 수 없습니다."`.
기간 합계는 단가가 있는 인스턴스만 더하고, `notes`에 빠진 인스턴스 수와 시간을 적는다.
조회 기간은 최대 455일이다.

## 2-1. 대시보드 요약 · 월 예산 (이슈 #27)
### `GET /aws/accounts/{accountId}/dashboard?currency=KRW|USD` — S06 상단 카드 4개
```json
{ "dataSource": "aws", "costBasis": "estimated_on_demand", "currency": "KRW", "exchangeRate": { … }, "timezone": "Asia/Seoul",
  "today": "2026-11-04", "month": "2026-11", "availableFrom": "2026-08-06",
  "monthToDate": { "from": "2026-11-01", "to": "2026-11-04", "usageHours": 1240.00, "estimatedCost": 128400 },
  "instanceCounts": { "running": 4, "stopped": 2 },
  "forecast": { "monthEndCost": 304200, "costThroughYesterday": 96300, "dailyAverage": 7700,
                "basisFrom": "2026-10-28", "basisTo": "2026-11-03", "basisDays": 7, "remainingDays": 27 },
  "budget": { "amount": 400000, "enteredAmount": 400000, "enteredCurrency": "KRW", "usageRate": 76.1, "thresholdReached": null },
  "previousMonthSamePeriod": { "currentFrom": "2026-11-01", "currentTo": "2026-11-03", "currentCost": 96300,
                               "previousFrom": "2026-10-01", "previousTo": "2026-10-03", "previousCost": 86000,
                               "change": 10300, "changeRate": 12.0 },
  "latestSpike": { "date": "2026-10-12", "cost": 9840, "ratio": 2.4, "baseline": 4100, "baselineDays": 30,
                   "causeInstanceId": "i-0abc", "causeName": "web-server-1",
                   "message": "10/12 비용 급증 · 평소의 2.4배 · web-server-1 사용 시간이 늘었어요" },
  "freshness": { … }, "notes": [ "월말 예상 = …" ], "asOf": "…" }
```
- **이번 달 추정 비용**(`monthToDate`): 1일 ~ 오늘(진행 중인 오늘 포함). 사용 시간 카드의 대수는 `instanceCounts`(지금 남아 있는 인스턴스의 실행 중·중지됨).
- **월말 예상**(`forecast`) = 어제까지 이번 달 추정 비용 + 최근 7일(어제까지, 기록 시작일 이후) 하루 평균 × 오늘부터 말일까지 남은 날.
  오늘은 아직 끝나지 않아 평균으로 센다. 끝난 날 기록이 없으면 `null` + `notes` 안내.
- **예산**(`budget`): 정하지 않았으면 `null`. `amount`는 요청 통화 기준(입력 통화와 같으면 입력값 그대로), `usageRate` = 월말 예상 ÷ 예산(%),
  `thresholdReached` = 넘은 가장 높은 알림 기준(80·100·120) 또는 `null`. 원화 예산인데 환율이 한 번도 없으면 `amount`·`usageRate`가 `null`.
- **전월 대비**(`previousMonthSamePeriod`): 이번 달 1일 ~ 어제 vs 전월 1일 ~ 같은 날 (끝난 날끼리, 전월이 짧으면 말일까지).
  `change`는 반올림한 금액끼리의 차, `changeRate`는 전월이 0이면 `null`. 매달 1일이거나 전월 1일이 기록 시작일보다 앞이면 `null` + `notes` 안내.
- 단가를 모르는 서버는 §2처럼 합계에서 빼고 `notes`로 알린다. **단가를 아는 서버가 하나도 없으면** 0원으로 보이지 않도록
  `monthToDate.estimatedCost`·`forecast`·`previousMonthSamePeriod`·`budget.usageRate`가 `null`이다 (사용 시간은 그대로).
- **최근 급증**(`latestSpike`, S06 배너): 최근 30일(오늘 포함) 중 가장 최근 급증일. 규칙은 §2 `costs/daily`의 `spike`와 같다. 없으면 `null`.
- 환율은 원화 예산이 있을 때만 조회한다 (응답 통화 환산은 §2와 같다).
- 오류: §2와 같다.

### `GET /aws/accounts/{accountId}/budget?currency=KRW|USD` — 월 예산 화면 (기능명세서 F08-1, 이슈 #41)
보기 모드(금액 + 예산 대비 %)와 수정 모드 슬라이더를 프론트 계산 없이 그린다. 저장은 아래 `PUT /budget`.
```json
{ "dataSource": "aws", "currency": "KRW", "exchangeRate": { … }, "accountId": "…",
  "budget": { "amount": 400000, "enteredAmount": 400000, "enteredCurrency": "KRW", "usageRate": 78.0, "thresholdReached": null },
  "forecastMonthEndCost": 312000,
  "recentAverage": { "monthlyCost": 286000, "months": ["2026-08", "2026-09", "2026-10"] },
  "slider": { "min": 10000, "max": 600000, "step": 10000, "suggested": 400000 },
  "notes": [ … ], "asOf": "…" }
```
- `budget`은 §2-1 대시보드의 `budget`과 같다 (없으면 `null`). `forecastMonthEndCost`도 같은 값이다.
- `recentAverage`: 이번 달 앞 **3개월** 중 기록 시작일 이후 **온전한 달**의 평균 추정 비용. 처음 쓰기 전의 달(기록 전체에서 처음 사용한 날 기준, 3개월 앞에서 이미 썼다면 사용이 없던 0원인 달도 넣는다)·단가를 모르는 달은 뺀다. 쓸 달이 없으면 `null` + `notes`.
- `slider` (요청 통화 기준): `step` 원화 1만 원 · 달러 10달러, `min` = 한 칸,
  `max` = 지난 3개월 평균 × 2 · 월말 예상 × 1.5 · 지금 예산 × 1.5 중 큰 값을 한 칸 단위로 올림.
  사용 근거(지난 3개월 평균·월말 예상)가 없으면 지금 예산이 있어도 기본값 100만 원 · 1,000달러 아래로 내려가지 않는다 (`max(지금 예산 × 1.5, 기본값)`, 저장할 때마다 범위가 좁아지지 않게),
  `suggested`(처음 위치) = 지금 예산 → 지난 3개월 평균 → 월말 예상 순으로 있는 값을 올림.
- 대시보드처럼 조회할 수 있는 연결이어야 한다 (대기·끊긴 연결은 409). 남의 연결은 404.
- 체험 모드: `POST /demo/budget` (§3 설정 + 선택 `budget`). 기간의 마지막 날이 속한 달 앞의 3개월로 평균을 낸다.

### `PUT /aws/accounts/{accountId}/budget` → 200
```json
요청 { "amount": 400000, "currency": "KRW" }   응답 { "accountId": "…", "amount": 400000, "currency": "KRW" }
```
- 입력한 통화 그대로 저장한다 (원화는 원 단위, 달러는 센트 단위로 반올림). 연결마다 하나이고 다시 보내면 바꾼다. 연결 상태와 관계없이 정할 수 있다.
- 400 `VALIDATION_ERROR`: 0 이하, 1조 초과, 소수점 아래 18자리 초과(예: `1E-1000000000`), 숫자가 아님, `amount`·`currency` 없음 (`details`에 필드별 문구). 남의 연결은 404.
### `DELETE /aws/accounts/{accountId}/budget` → 204
예산을 지운다 ("설정 안 함"). 연결을 삭제하면 예산도 함께 지운다.

## 3. 체험 모드 — 실제 계정 없이 (ADR-008)
Google 로그인 없이 간이 로그인 → 서버 구성·기간 설정 → 실제 연결 계정과 같은 형식의 비용을 본다. AWS·DB를 부르지 않는다.
체험용 계정은 연결 목록(`GET /aws/accounts`)에 들어가지 않고, 실제 연결 경로로는 조회할 수 없다.

### `POST /demo/login` → 200 / 401 `DEMO_LOGIN_FAILED`
```json
요청 { "username": "cloudcost", "password": "1234" }
응답 { "dataSource": "demo", "accountId": "00000000-0000-4000-8000-00000000d3e0", "name": "체험용 AWS 계정",
       "awsAccountId": "123456789012", "region": "ap-northeast-2" }
```
실제 AWS 자격증명이 아니라 화면에 안내하는 공개 값이다 (`CCI_DEMO_USERNAME` / `CCI_DEMO_PASSWORD`로 바꿀 수 있음). 토큰은 주지 않는다.

### `GET /demo/catalog`
고를 수 있는 `instanceTypes`(타입별 Linux·Windows 시간당 USD), `os`(`LINUX`|`WINDOWS`),
`schedules`(`ALWAYS` 24시간 · `WEEKDAY_OFFICE` 평일 09~19시 · `DAILY_HOURS` 매일 09시부터 N시간 ·
`MONTHLY_SPIKE` 매일 09시부터 N시간이지만 매달 12일은 24시간 — 급증 체험용, `hoursPerDay` 필요),
`usagePatterns`(켜져 있는 동안의 CPU·네트워크 모양: `NORMAL` 업무 시간 CPU 40~70% · `OVERSIZED` CPU 3~8% · `IDLE` CPU 1% 미만·네트워크 거의 없음),
한도(`maxServers` 20, `maxCountPerServer` 10, `maxInstances` 50).

### 비용 조회: `POST /demo/instances` · `/demo/costs` · `/demo/costs/daily` · `/demo/costs/monthly` (`?currency=KRW|USD`)
```json
{ "from": "2026-10-01", "to": "2026-10-31",
  "servers": [ { "name": "web", "instanceType": "m5.large", "os": "LINUX", "count": 2, "schedule": "ALWAYS", "usagePattern": "OVERSIZED" },
               { "name": "gpu", "instanceType": "g4dn.xlarge", "os": "LINUX", "count": 1, "schedule": "DAILY_HOURS", "hoursPerDay": 6 } ] }
```
- 응답은 §2의 실제 경로(`GET /aws/accounts/{accountId}/…`)와 같은 형식이다. 기간은 기본값 없이 요청의 `from`~`to`를 쓰고, 월별은 그 기간을 월로 나눈다.
- 다른 점: `dataSource: "demo"`, `accountId`는 위 고정값, `notes` 맨 앞에 체험 데이터·예시 단가 안내, `freshness`의 시각은 모두 `null`, `availableFrom`은 요청의 `from`.
- `from` 0시에 모든 서버를 처음 켰다고 보고, 지난 시간까지만 계산한다(현재 진행 중인 시간 제외). 인스턴스 이름은 대수가 2대 이상이면 `web-1`, `web-2`…
- `usagePattern`은 선택이고 없으면 `NORMAL`이다. 비용 응답에는 드러나지 않고, 낭비 탐지(`POST /demo/waste`, §4)·벤치마킹 체험에 쓰인다.
  값은 인스턴스 ID·시각으로 정한 결정적 값이라 같은 설정이면 항상 같다.
- 단가는 서울 리전 온디맨드 단가를 고정값으로 쓴다 (Price List를 부르지 않음). 금액 계산은 실제 경로와 같은 domain 로직이다.
- `POST /demo/costs/daily`도 `days[].spike`를 준다. 체험 모드도 실제 연결처럼 처음 켜진 날부터 평소를 잰다 (`from`이 주말인 평일 일정이면 첫 평일부터. `from` 앞은 기록이 없다).
  예: `from` 9/1, `MONTHLY_SPIKE`·`hoursPerDay` 4인 서버가 있으면 9/12가 급증일이 된다 (평소를 잰 날 11일).
- 잘못된 설정은 400 `DEMO_INVALID_SETUP` (이유는 `message`): 모르는 타입, 대수 1~10 밖, 합계 50대 초과, `to`가 오늘 이후 등. 기간 455일 초과는 `INVALID_PERIOD`.

### `POST /demo/dashboard` (`?currency=KRW|USD`) — 체험 모드 대시보드 요약
요청은 위와 같은 설정에 선택 필드 `budget`(`{ "amount": 400000, "currency": "KRW" }`)을 더한다. 응답은 §2-1과 같다.
기간의 마지막 날(`to`)을 오늘로 본다. 서버는 `from` 0시에 처음 켰다고 보므로 그 앞의 날은 0이고, `from`이 전월 1일보다 늦으면 전월 비교는 `null`이다.

### `POST /demo/waste` (`?currency=KRW|USD`) — 체험 모드 낭비 리소스
요청은 위와 같은 설정. 기간의 **마지막 14일**(아직 지나지 않은 시간 제외)을 서버별 `usagePattern`으로 판단한다. 응답은 §4와 같다 (`?sort=&type=`도 같다).
체험 단가 카탈로그에 있는 t3(micro·small·medium·large)만 후보가 된다.
기간이 14일보다 짧으면 `status: "INSUFFICIENT_HISTORY"`, `availableAt` = `from` + 14일.

## 4. 낭비 리소스 (이슈 #10, ADR-010)
### `GET /aws/accounts/{accountId}/waste?currency=KRW|USD&sort=SAVINGS&type=`
최근 14일(`window`, 정시 기준) EC2 사용률(#24, ADR-009)로 과다 사양·유휴 서버를 찾는다. **조언만 한다** (중지·변경 버튼 없음).
S07 목록과 S07-2 추천 상세는 이 응답 하나로 그린다 (상세 API 없음).
```json
{ "dataSource": "aws", "costBasis": "estimated_on_demand", "currency": "KRW", "exchangeRate": { … },
  "status": "OK", "availableAt": null,
  "window": { "from": "2026-10-21T02:00:00Z", "to": "2026-11-04T02:00:00Z" },
  "totalMonthlySavings": 132000, "forecastMonthEndCost": 304200, "savingsRateOfForecast": 43.4, "findingCount": 3, "counts": { "oversized": 2, "idle": 1 }, "judgedCount": 5,
  "items": [ {
      "recommendationId": "i-0abc:OVERSIZED", "instanceId": "i-0abc", "name": "api-server", "instanceType": "m5.large",
      "state": "running", "kind": "OVERSIZED", "kindLabel": "과다 사양",
      "evidence": "최근 14일 CPU 평균 6% (P95 9%)", "action": "m5.large → t3.medium 으로 변경 검토",
      "reason": "바꿔도 P95 9%가 t3.medium 기본 성능 20% 안 · 조건에 맞는 타입 중 가장 저렴", "memoryCheckNeeded": true,
      "summary": [
        { "title": "거의 안 쓰고 있어요", "detail": "14일 평균 CPU 6%, 가장 높을 때도 23%예요" },
        { "title": "줄여도 넉넉해요", "detail": "바꿔도 예상 사용률 9%, 기본 성능(20%) 안이에요" },
        { "title": "조건을 만족하는 것 중 가장 싸요", "detail": "시간당 ₩72.1 (다음 후보 t3.large ₩144.2)" } ],
      "summaryNote": "메모리(8 → 4 GiB)는 확인하지 못했어요. 바꾸기 전에 서버에서 직접 확인해 주세요.",
      "basis": { "projectedCpuP95": 9.0, "targetBaselinePercent": 20,
                 "nextCandidate": { "instanceType": "t3.large", "hourlyCost": 144.2 } },
      "cpuChart": { "points": [ { "hour": "2026-10-21T02:00:00Z", "avg": 5.80, "max": 7.10 }, … ],
                    "max": 23.00, "maxAt": "2026-10-29T05:00:00Z",
                    "baselinePercent": 20, "baselineLabel": "t3.medium 기본 성능 20%" },
      "metrics": { "cpuAvg": 6.12, "cpuP95": 9.00, "cpuMax": 23.00, "networkBytes": 3523215360, "cpuHours": 336 },
      "monthlyHours": 730.00, "estimatedMonthlyCost": 118300, "estimatedMonthlySavings": 65700,
      "comparison": {
        "current":     { "instanceType": "m5.large",  "vcpu": 2, "memoryGib": 8, "hourlyCost": 162.1, "monthlyCostAt730h": 118300 },
        "recommended": { "instanceType": "t3.medium", "vcpu": 2, "memoryGib": 4, "hourlyCost": 72.1,  "monthlyCostAt730h": 52600 } },
      "cautions": [ "메모리 사용률은 CloudWatch 기본 지표에 없어 판단에 넣지 않았어요. …", … ],
      "consoleSteps": [ "EC2 → 인스턴스에서 api-server을(를) 중지해요 …", … ] },
    … (나머지 2건 생략) ],
  "skipped": [ { "instanceId": "i-0gpu", "name": "gpu-test", "reason": "CPU를 적게 쓰지만 GPU 타입이라 …" } ],
  "freshness": { … }, "notes": [ "…미연결 볼륨 · 미사용 고정 IP · 스냅샷 · 로드밸런서는 지금 연결 권한으로는 찾을 수 없어요." ], "asOf": "…" }
```
- `status`: `OK`(추천 있음) · `NONE_FOUND`(판단했지만 줄일 서버 없음, S07-3 A) · `INSUFFICIENT_HISTORY`(기록 시작일부터 14일 미만, S07-3 B. `availableAt`부터 판단).
  연결 오류(S07-3 D)는 §2처럼 저장된 기록으로 판단하고 `freshness.warnings`에 원인이 붙는다.
- 규칙 (하네스 기능명세서 F05, ADR-016. 최근 14일 동안 **24시간 이상** 켜져 있던 서버만 판단, `judgedCount`):
  - **유휴** `IDLE`: CPU **최대** < 5% 그리고 네트워크 입출력 하루 평균 < 5MB → 중지 검토. 절감액 = 월 추정 비용 전부. 네트워크를 아직 모르면(켜져 있던 시간 중 하나라도 입력·출력 값이 비어 있으면) 유휴로 보지 않는다. GPU 타입과 사양표에 없는 타입(GPU인지 알 수 없음)은 CPU·네트워크만으로 쉬는지 알 수 없어 판단하지 않고, 팀 벤치마킹의 판단 서버 수에도 넣지 않는다.
  - **과다 사양** `OVERSIZED`: CPU **평균** < 10% 그리고 **P95** < 40% (유휴 아님) → 바꿀 타입을 아래로 고른다.
    - 후보: t3 패밀리 중 메모리가 지금의 **절반 이상**인 타입 (메모리를 절반 아래로 줄이지 않는다).
    - 바꾼 뒤 예상 P95 = 현재 P95 × 현재 vCPU ÷ 새 vCPU 가 새 타입 **기본 성능**(vCPU당, t3.nano 5% · micro 10% · small·medium 20% · large 30% · xlarge·2xlarge 40%) 이하이고
      지금보다 싼 후보 중 **시간당 단가가 가장 싼 것** (예: m5.large P95 9% → t3.medium, m5.xlarge P95 12% → t3.large, P95 18% → t3.xlarge).
    - `reason`: 위 계산을 한 줄로 ("바꿔도 P95 9%가 t3.medium 기본 성능 20% 안 · 조건에 맞는 타입 중 가장 저렴").
    - `memoryCheckNeeded`: 메모리가 줄어드는 추천이면 `true`, `cautions` 첫 줄에 "메모리가 8GiB → 4GiB로 줄어요 … 직접 확인이 필요해요".
- **S07-2 추천 상세** (와이어프레임 그대로 그리는 값. 프론트는 계산하지 않는다):
  - `summary`: 10초 요약 3줄(`title` 굵게 · `detail`). 과다 사양 = 사용률(평균·최대) / 바꾼 뒤 예상 사용률과 기본 성능 / 시간당 단가와 다음 후보.
    유휴 = 최대 CPU / 네트워크 / 매달 절감 예상. 금액은 요청 통화로 적는다. `detail`은 서버마다 다른 숫자이고, `title`은 사용량 구간에 따라 아래처럼 바뀐다.

    | 줄 | 조건 → `title` |
    |---|---|
    | 사용량 | 평균 CPU(유휴는 최대) 1% 미만 "사실상 쉬고 있어요" · 8% 미만 "거의 안 쓰고 있어요" · 그 밖 "적게 쓰고 있어요" |
    | 여유 (과다 사양) | 예상 사용률 ≤ 기본 성능의 절반 "줄여도 넉넉해요" · 그 밖 "줄여도 기본 성능 안이에요" |
    | 가격 (과다 사양) | 다음 후보 있음 "조건을 만족하는 것 중 가장 싸요" · 없음 "조건을 만족하는 타입은 이것뿐이에요" (설명에 괄호 없음) |
    | 네트워크 (유휴) | 하루 평균 1MB 미만 "네트워크도 거의 없어요" · 그 밖 "네트워크도 조용해요" (설명: "하루 평균 입출력이 3MB예요") |
    | 절감 (유휴) | "중지하면 그만큼 줄어요" |

    사용량 줄 설명은 최대 CPU가 50% 이상이면 "가장 높을 땐 80%까지 올라갔어요", 아니면 "가장 높을 때도 23%예요".
  - `summaryNote`: 요약 아래 경고 한 줄. 메모리가 줄어드는 추천일 때만, 아니면 `null`.
  - `basis`(과다 사양만, 유휴면 `null`): `projectedCpuP95`(바꾼 뒤 예상 P95), `targetBaselinePercent`(추천 타입 기본 성능),
    `nextCandidate`(조건을 만족하는 다음으로 싼 타입과 시간당 단가. 없으면 `null`).
  - `cpuChart`: 최근 14일 시간별 CPU(`points`, UTC 정시. 꺼져 있던 시간은 점이 없다), 가장 높았던 값·시간(`max`·`maxAt`, KST 표시는 프론트),
    점선 기준선 `baselinePercent`·`baselineLabel`(추천 타입 기본 성능. 유휴면 `null`). 추천이 있는 서버에만 준다.
    - 절감액 = 월 비용 차이. 사양을 모르는 타입·GPU 타입·단가를 모르거나 조건에 맞는 더 싼 타입이 없으면 추천하지 않고 `skipped`에 이유를 적는다.
- **정렬·필터** (F05-5): `sort` = `SAVINGS`(절감액 큰 순, 기본) · `COST`(월 추정 비용 큰 순) · `UTILIZATION`(최근 14일 평균 CPU 낮은 순) · `NAME`(이름순).
  같으면 절감액 큰 순 → 이름순. `type` = `OVERSIZED` · `IDLE`(없으면 모두). 응답의 `sort`·`type`에 적용한 값을 돌려준다.
  `items`만 정렬·필터하고, `findingCount`·`counts`·`totalMonthlySavings`는 전체 기준이다. 모르는 값은 400 `VALIDATION_ERROR`.
- `recommendationId`(`{instanceId}:{kind}`)는 시뮬레이터·벤치마킹에 그대로 보낸다.
- 지금 꺼져 있어도 최근 14일 안에 켜져 있었으면 판단한다. 단, **지금 중지돼 있고 최근 3일 동안 한 번도 안 켜진 서버**는 이미 비용이 나지 않으므로
  판단하지 않고 `skipped`에 이유를 적는다 (밤·주말에 꺼지는 서버가 조회 시각에 따라 빠지지 않게 "지금 중지"만으로는 빼지 않는다).
  삭제된 인스턴스(`present=false`)는 빼고, 14일 동안 한 번도 안 켜진 서버는 목록에 넣지 않는다.
- `forecastMonthEndCost`·`savingsRateOfForecast`(S07 "이번 달 예상 비용 대비 %"): §2-1 월말 예상과 같은 값, 절감 예상 ÷ 월말 예상(%). 월말 예상이 없으면 `null`.
- 오류: §2와 같다 (404 남의 연결, 409 대기·끊긴 연결, 429/502 AWS).


## 4-0. 방치 리소스·잊힌 리전 (ADR-020)
`GET /aws/accounts/{accountId}/waste` 응답에 `resources`가 붙고, 같은 내용을 `GET /aws/accounts/{accountId}/resources/waste?currency=KRW|USD`로 따로 받을 수 있다
(응답: `{ dataSource, exchangeRate, accountId, resources }`). 체험 모드 `POST /demo/waste`에도 예시 `resources`가 붙는다.
켜진 모든 리전을 6시간에 한 번 Describe로 훑는다 (예약 수집 뒤에도). 위 `items`(서버 사양·유휴)와 따로 센다.
조회는 수집을 기다리지 않는다: 결과가 오래됐으면 백그라운드에서 다시 훑고 저장된 결과를 바로 주며 `refreshing: true`를 붙인다
(첫 조회는 `scan.status: NOT_SCANNED` + `refreshing: true` → 화면은 "확인 중"을 보이고 잠시 뒤 다시 조회한다).
```json
"resources": {
  "costBasis": "estimated_list_price", "currency": "KRW", "homeRegion": "ap-northeast-2",
  "scan": { "status": "OK", "permissionVersion": 2, "scannedAt": "…", "message": null },
  "upgrade": null, "refreshing": false,
  "findingCount": 5, "totalMonthlyCost": 84000,
  "otherRegions": [ { "region": "us-east-1", "runningServers": 1 } ],
  "items": [ { "recommendationId": "us-east-1:i-0abc:FORGOTTEN_REGION", "kind": "FORGOTTEN_REGION", "kindLabel": "잊힌 리전의 서버",
               "resourceType": "INSTANCE", "resourceTypeLabel": "서버", "resourceId": "i-0abc", "name": "test-server",
               "region": "us-east-1", "otherRegion": true, "monthlyCost": 41000,
               "evidence": "연결한 기본 리전이 아닌 us-east-1에서 t3.medium 서버가 실행 중이에요", "action": "…",
               "consoleSteps": [ "…" ], "cautions": [ "…" ], "priceNote": null } ],
  "notes": [ "볼륨·고정 IP·스냅샷·로드밸런서 금액은 AWS 공개 정가로 계산한 월 추정치예요 …", "CloudCost는 읽기만 해요 …" ] }
```
| `kind` | `resourceType` | 조건 | 월 추정 |
|---|---|---|---|
| `UNATTACHED_VOLUME` | `VOLUME` | 어떤 서버에도 연결 안 됨 (available) | 크기 × 볼륨 타입 GB-월 정가 |
| `UNUSED_EIP` | `ADDRESS` | 연결 안 된 고정 IP | $0.005 × 730 |
| `OLD_SNAPSHOT` | `SNAPSHOT` | 내 스냅샷, 만든 지 90일 초과 | 원본 크기 × $0.05 (최대치) |
| `IDLE_LB` | `LOAD_BALANCER` | 만든 지 14일 초과, 지금까지 14일 요청 0 (ALB RequestCount + 거부한 요청 HTTPCode_ELB_5XX_Count / NLB NewFlowCount) | 시간당 기본 요금 × 730 (LCU 제외) |
| `FORGOTTEN_REGION` | `INSTANCE` | 기본 리전 밖에서 실행 중 | 온디맨드 단가 × 730 |
- 정렬: 월 추정 큰 순. 금액을 모르면 `monthlyCost: null` + `priceNote`에 사유. 서울·버지니아 밖 리전의 정가는 버지니아 값으로 추정하고 `priceNote`에 적는다.
- `totalMonthlyCost`: 금액을 모르는 항목이 하나라도 있으면 `null`(부분합을 전체처럼 보이지 않게, `notes`에 안내). 발견 항목이 없으면 0.
- `scan.status`: `NOT_SCANNED`(수집할 수 없는 연결) · `OK` · `FAILED`(일시 오류, 이전 목록 유지, `message`) ·
  **`PERMISSION_UPGRADE_REQUIRED`**: 확장 권한이 거부됐다. 대개 이전 템플릿(v1)이지만 SCP·권한 경계·Deny 정책일 수도 있어 단정하지 않는다
  (`permissionVersion: 1` = 확인되지 않음, `message`에 거부된 액션과 두 가능성). 오류가 아니라 안내다 — `upgrade`에 방법이 온다:
  - 빠른 생성 스택으로 연결(`stackId` 있음): `{ "method": "STACK", "stackName": "CloudCostInsight-…", "consoleUrl": "https://console.aws.amazon.com/cloudformation/home?region=<스택이 있는 리전>…", "templateUrl": "https://…/customer-readonly-role.yaml", "permissionPolicy": null, "steps": [ … ] }`
    (리전·스택 이름은 저장된 스택 ARN에서 읽는다. 수집 리전과 다를 수 있다)
  - 직접 만든 Role(`PUT …/role`): `{ "method": "MANUAL_ROLE", "stackName": null, "consoleUrl": "https://console.aws.amazon.com/iam/home#/roles/details/<Role 이름>", "templateUrl": null, "permissionPolicy": { …7개 액션 정책 JSON… }, "steps": [ … ] }`
  1시간마다 다시 확인해, 권한이 생겼으면 `OK`·`permissionVersion: 2`가 된다. 서버 낭비(`items`)와 연결 상태에는 영향이 없다.

## 4-1. 비용 시뮬레이터 (이슈 #31, ADR-012)
### `POST /aws/accounts/{accountId}/simulations?currency=KRW|USD` — S08 · S08-2
저장하지 않는 계산이다. 추천은 §4 낭비 리소스 응답의 `recommendationId`를 그대로 보낸다 (기본 전체 선택은 프론트가 처리).
```json
요청 { "recommendationIds": ["i-0abc:OVERSIZED", "i-0def:IDLE"], "trafficGrowthPercent": 20 }
응답 { "dataSource": "aws", "costBasis": "estimated_on_demand", "currency": "KRW", "exchangeRate": { … },
       "status": "OK", "trafficGrowthPercent": 20, "selectedCount": 2,
       "selected": [ { "recommendationId": "i-0abc:OVERSIZED", "instanceId": "i-0abc", "name": "api-server", "kind": "OVERSIZED",
                       "kindLabel": "과다 사양", "action": "m5.large → t3.medium 으로 변경 검토", "monthlySavings": 65700 } ],
       "selectedMonthlySavings": 132000, "forecastMonthEndCost": 312000,
       "before": { "monthlyCost": 374400, "budgetRate": 93.6 }, "after": { "monthlyCost": 216000, "budgetRate": 54.0 },
       "monthlySavings": 158400, "yearlySavings": 1900800,
       "budget": { "amount": 400000, "enteredAmount": 400000, "enteredCurrency": "KRW" }, "withinBudget": true,
       "freshness": { … }, "notes": [ … ], "asOf": "…" }
```
- 적용 전(`before`) = 월말 예상(§2-1과 같은 값) × (1 + 증가율). 적용 후(`after`) = (월말 예상 − 고른 추천의 월 절감액 합) × (1 + 증가율), 0 아래로 내려가지 않는다.
  트래픽이 늘면 서버 사용량도 같은 비율로 는다고 가정한다.
- `monthlySavings` = 적용 전 − 적용 후, `yearlySavings` = `monthlySavings` × 12 (USD로 계산한 뒤 환산).
- `trafficGrowthPercent`는 0~100 정수, 없으면 0. 소수(예: 20.5)는 버리지 않고 400으로 거절한다. `recommendationIds`가 없거나 비면 추천 없이 계산한다 (적용 전 = 적용 후). 중복 id는 한 번만 센다.
- 예산이 있으면 `budgetRate`(예산 대비 %)와 `withinBudget`(적용 후 ≤ 예산), 없으면 `budget`·`budgetRate`·`withinBudget`이 `null`.
- 월말 예상이 없으면(끝난 날 기록 없음·단가를 아는 서버 없음) `status: "NO_FORECAST"`이고 금액 필드가 `null`, `notes`에 안내.
- 오류: 400 `UNKNOWN_RECOMMENDATION`(지금 추천 목록에 없는 id, `message`에 id), 400 `VALIDATION_ERROR`(증가율 범위 밖·소수, id 200개 초과), 그 밖은 §2와 같다.

### `POST /demo/simulations?currency=KRW|USD` — 체험 모드
§3 설정에 `recommendationIds`·`trafficGrowthPercent`·`budget`(모두 선택)을 더한다. 추천 id는 같은 설정의 `POST /demo/waste` 응답에서 나온 값이다.
기간의 마지막 날을 오늘로 보고 같은 계산을 한다.

## 4-2. 팀 벤치마킹 (이슈 #33, ADR-013)
### `GET /aws/accounts/{accountId}/benchmark?recommendationIds=&sizeBand=&currency=KRW|USD` — S09 · S09-2~4
비슷한 규모 팀의 공개 자료와 견준다. 비교 기준은 교체 가능한 파일(`CCI_BENCHMARK_REFERENCE`, 기본 `classpath:benchmark/reference.json`)이다.
```json
{ "dataSource": "aws", "currency": "KRW", "status": "OK", "availableAt": null, "window": { "from": "…", "to": "…" },
  "appliedRecommendationIds": [], "selectedMonthlySavings": null,
  "oversizedRatio": {
    "status": "OK", "ours": { "judgedCount": 4, "oversizedCount": 2, "ratio": 50.0 }, "moreEfficientTeamsPercent": 55.0,
    "matchedSizeBand": "3-20",
    "reference": { "source": "Azure Public Dataset V2", "description": "마이크로소프트 내부 서비스 VM 30일 기록 중 같은 VM 대수 구간 구독들의 … 참고용",
                   "sizeBand": "3-20", "sizeBandLabel": "서버 3~20대", "dataAvailable": true, "sampleSize": 2622,
                   "averageRatio": 42, "medianRatio": 33, "percentileAvailable": true },
    "sizeBands": [ { "key": "1-2", "label": "서버 1~2대", "minServers": 1, "maxServers": 2, "dataAvailable": false, "matchesOurs": false },
                   { "key": "3-20", "label": "서버 3~20대", "minServers": 3, "maxServers": 20, "dataAvailable": true, "matchesOurs": true } ],
    "distribution": { "approximate": true, "basis": "백분위 표(5% 간격)로 어림한 분포라 …", "sizeBand": "3-20",
                      "buckets": [ { "fromPercent": 0, "toPercent": 20, "label": "0~20%", "teamsPercent": 30.0, "ours": false, "afterSimulation": false },
                                   { "fromPercent": 40, "toPercent": 60, "label": "40~60%", "teamsPercent": 15.0, "ours": true, "afterSimulation": false }, … ],
                      "oursBucketIndex": 2, "afterSimulationBucketIndex": null },
    "servers": [ { "instanceId": "i-0abc", "name": "api-server", "instanceType": "m5.large", "cpuP95": 6.20, "cpuHours": 336,
                   "oversized": true, "verdict": "과다 사양 (P95 10% 미만)" } ],
    "afterSimulation": null },
  "wasteRatio": { "status": "OK", "reason": null, "ratioPercent": 60.5, "comparison": "ABOVE",
                  "amounts": { "serverWaste": 158000, "resourceWaste": 4900, "waste": 162900, "total": 269000, "wasteUsd": 121.91, "totalUsd": 201.55 },
                  "afterSimulationRatioPercent": null,
                  "referenceSource": "Flexera 2026 State of the Cloud", "referenceDescription": "…", "referenceAverage": 29 },
  "monthlySpend": { "status": "OK", "monthlyCost": 263824, "monthlyCostUsd": 197.90, "monthlyVcpuHours": 4320.0, "servers": 3,
                    "teamsUsingLessPercent": 35.0, "position": "WITHIN", "barPositionPercent": 60.9,
                    "range": { "source": "Azure Public Dataset V2", "description": "…", "servers": 3, "label": "서버 3대",
                               "min": 144454, "max": 340378, "minUsd": 108.36, "maxUsd": 255.33,
                               "minVcpuHours": 2365.3, "maxVcpuHours": 5573.6, "medianVcpuHours": 4320.0,
                               "model": { "method": "QUANTILE_REGRESSION", "sampleSize": 5452, "minServers": 1, "maxServers": 50,
                                          "testSize": 1118, "improvementPercent": 15.3, "middleHalfCoveragePercent": 51.9 } },
                    "afterSimulation": null },
  "freshness": { … }, "notes": [ … ], "asOf": "…" }
```
- **① 과다 사양 비율**(`oversizedRatio`) = CPU P95 < 10%인 서버 수 ÷ 판단한 서버 수 (최근 14일 중 **24시간 이상** 켜진, 지금 남아 있는 서버).
  §4 낭비 탐지와 같은 14일 구간·지표를 쓴다. `servers`는 P95 낮은 순 (S09-3).
  - **서버 대수 구간**: 비교 자료는 서버(VM) 대수 구간별 분포다 (기본 `1-2`·`3-20`). 판단한 서버 수로 우리 구간(`matchedSizeBand`)을 정해 그 구간과 견준다.
    `sizeBand`(예: `1-2`)를 주면 그 구간과 견준다 (S09 규모 선택, "그 규모 팀과 비교하면"). 고를 수 있는 값은 `sizeBands`, 모르는 값은 400 `VALIDATION_ERROR`.
  - `status`: `OK` · `NOT_COMPARABLE`(판단한 서버가 없거나, 맞는 구간이 없거나, 고른 구간의 비교 자료가 아직 없음(`reference.dataAvailable: false`) — 비율·목록은 주지만 비교하지 않음) ·
    `INSUFFICIENT_HISTORY`(기록 14일 미만, `ours`·`servers` 비움).
  - 기본 파일은 Azure 원본(VM 2,695,548대·구독 6,687곳)을 `scripts/benchmark/AzureOversized.java`로 계산한 값이다:
    `1-2` 구독 1,473곳 평균 47%·중앙값 50%, `3-20` 구독 2,622곳 평균 42%·중앙값 33% (ADR-013).
  - `moreEfficientTeamsPercent`: 우리보다 비율이 **확실히 낮은** 팀의 비율(%). 백분위 표에서 비율이 우리보다 낮은 가장 큰 백분위다
    (5% 단위 하한값. 같은 비율인 팀은 넣지 않는다 — 1~2대 팀은 비율이 0·50·100%뿐이라 같은 값이 많다). 표가 없으면 `null` + `notes` 안내.
  - `distribution`: 비교 구간(`reference.sizeBand`) 팀들의 과다 사양 비율 분포. 0~20·20~40·40~60·60~80·80~100% 다섯 칸(하한 포함, 100%는 마지막 칸)이고
    `teamsPercent`를 모두 더하면 100이다. 원본이 아니라 **백분위 표로 어림한 값**(`approximate: true`)이라 칸마다 5%p 정도 틀릴 수 있다.
    우리 비율이 드는 칸은 `ours`·`oursBucketIndex`, 추천 적용 후는 `afterSimulation`·`afterSimulationBucketIndex`. 비교 구간이나 백분위 표가 없으면 `null`.
- **② 전체 낭비 비율**(`wasteRatio`, ADR-020): `ratioPercent` = (서버 낭비 월 절감액 + 방치 리소스 월 비용) ÷ (월말 예상 + 방치 리소스 월 비용) × 100, 소수 첫째 자리, 최대 100.
  - 서버 낭비 = §4 과다 사양·유휴 추천의 월 절감액 합, 방치 리소스 = §4-0 `resources.totalMonthlyCost` (볼륨·고정 IP·스냅샷·LB·잊힌 리전 서버).
    월말 예상(EC2)에는 EBS·IP·LB가 없어 방치 리소스 비용을 분모에도 더한다. 사용 중인 볼륨·LB 비용은 몰라 분모에 없으므로 실제보다 조금 높게 나올 수 있다(`notes`에 안내).
  - `comparison`: Flexera 업계 평균(`referenceAverage`)보다 `BELOW`·`SAME`·`ABOVE`. `amounts`: 금액(currency 기준) + `wasteUsd`·`totalUsd`.
  - `afterSimulationRatioPercent`: `recommendationIds`를 주면 서버 낭비에서 고른 추천 절감액을 빼고 월말 예상도 시뮬레이터 적용 후 금액으로 바꿔 다시 잰다 (방치 리소스는 그대로).
  - `status`가 `OK`가 아니면 `ratioPercent`·`comparison`·`amounts`는 `null`이고 `reason`에 사유가 있다:
    `UNAVAILABLE` — 권한 업데이트 필요(`PERMISSION_UPGRADE_REQUIRED`) · 방치 리소스 확인 중/확인 전 · 확인 실패 · 단가를 모르는 방치 리소스가 있음 (일부만 더해 낮게 보이지 않도록) /
    `INSUFFICIENT_HISTORY` — 기록 14일 미만 / `NO_FORECAST` — 월말 예상 없음.
  - 방치 리소스는 저장된 결과를 쓰고 기다리지 않는다 (§4-0 `refreshing`). 체험 모드는 §4-0 예시 리소스를 더한다.
- **③ 규모 대비 월 지출**(`monthlySpend`): 우리 서버 대수와 같은 규모 팀들과 **한 달 사용량(vCPU-시간 = vCPU × 켜진 시간)**으로 순위를 매긴다 (ADR-013).
  - 같은 규모 팀의 사용량 분포는 **분위 회귀 모델**이 서버 대수로 예측한다 (`range.model`). Azure 원본 구독 5,452곳(서버 1~50대)으로
    `scripts/benchmark/AzureSpend.java`가 학습했고, 학습에 쓰지 않은 1,118곳으로 검증해 기존 구간별 백분위 표보다 오차(pinball loss)가 15.3% 작고
    예측한 25~75번째 범위에 실제 값이 51.9% 들어왔다. 서버 1~50대 밖이면 비교하지 않는다. `sizeBand`는 ①에만 쓰고 월 지출은 우리 서버 대수 그대로다.
  - 우리 사용량 `monthlyVcpuHours` = Σ 코어 구간 vCPU(2·4·8·24, 24 초과는 32) × 최근 14일 켜진 시간(①과 같은 구간) × 30 ÷ 14. `servers`는 센 서버 수
    (14일 중 1시간 이상 켜졌고 vCPU를 아는 서버).
  - `teamsUsingLessPercent`: 같은 규모 팀 중 우리보다 사용량이 **확실히 적은** 팀(%, 5% 단위 하한. 같은 사용량은 넣지 않는다).
  - `range`: 모델이 예측한 같은 규모 팀의 가운데 절반(25~75번째 백분위) 사용량을 **우리 팀의 vCPU-시간당 비용**(월말 예상 ÷ 우리 사용량)으로 환산한 금액.
    비교 자료에 가격이 없어 금액이 아니라 사용량으로 순위를 매기므로, 단가 가정이 순위에 영향을 주지 않는다. `position`·`barPositionPercent`는 월말 예상을 이 범위와 견준 값.
  - `status`: `OK` · `NO_FORECAST`(월말 예상 없음, 금액 `null`) · `INSUFFICIENT_HISTORY`(기록 14일 미만, 금액만 줌) ·
    `NOT_COMPARABLE`(켜진 서버가 없거나 1~50대 밖, 금액·사용량만 줌). OK가 아니면 `range`·순위·위치는 `null`.
  월 지출은 EC2 온디맨드 추정이라 EBS·전송 비용이 빠진다는 안내와 월말 예상의 안내(§2-1 `notes`)도 이 응답 `notes`에 함께 준다 (같은 문구는 한 번만).
- **`recommendationIds`**(쉼표 구분 또는 여러 번)를 주면 `afterSimulation`을 채운다: 과다 사양 추천을 적용한 서버는 적정, 유휴 추천(중지)을 적용한 서버는 판단 대상에서 빠진다고 보고,
  월 지출은 §4-1 시뮬레이터와 같은 계산(증가율 0)의 적용 후 금액을 같은 범위와 견줘 위치를 다시 잡는다. 모르는 id는 400 `UNKNOWN_RECOMMENDATION`.
  `selectedMonthlySavings`는 고른 추천의 월 절감액 합으로 §4-1 응답의 같은 이름 값과 같다 (추천을 주지 않으면 `null`). 절감액 문구를 위해 시뮬레이션을 따로 부르지 않아도 된다.
- 최상위 `status`: `OK` 또는 `INSUFFICIENT_HISTORY`(+ `availableAt`).
- 오류: §2와 같다.

### `POST /demo/benchmark?currency=KRW|USD` — 체험 모드
§3 설정에 `recommendationIds`(선택)를 더한다. `?sizeBand=`도 실제와 같다. 기간의 마지막 14일을 서버별 `usagePattern`으로 판단한다 (`OVERSIZED`·`IDLE`은 과다 사양, `NORMAL`은 적정).

## 5. 알림 (이슈 #35, ADR-014 · ADR-019)
알림은 서비스 안 알림창(헤더 벨·알림 목록)에 보여 주고, **등록한 Discord 채널로도 보낸다** (§5-1).
조건은 **예약 수집 뒤(기본 하루 4번, KST 03·09·15·21시)**와 **알림 목록·안 읽은 수를 조회할 때** 연결마다 확인해 새로 생긴 일만 알림으로 저장한다.
수집할 수 없는 연결(대기·끊김)과 AWS 오류가 난 연결은 그 조회에서 건너뛴다 (목록 조회는 실패하지 않는다).

| 종류 `type` | 조건 (설정에서 켠 것만) | 한 번만 (`event_key`) | 문구 예 |
|---|---|---|---|
| `BUDGET` | §2-1 `thresholdReached`(월말 예상 기준) 이하에서 켜 둔 가장 높은 기준 | 연결 · 월 · 기준 | 운영 서버의 이번 달 예상 비용이 예산의 80%를 넘었어요 |
| `SPIKE` | §2-1 `latestSpike` (최근 30일 중 가장 최근 급증) | 연결 · 급증일 | 10/12 비용 급증 · 평소의 2.4배 (web-server-1) |
| `WEEKLY_SUMMARY` | 지난주(월~일, KST)가 모두 기록 시작일 이후이고 두 주 중 한 주라도 비용이 있음 | 연결 · 주 | 지난주 비용 ₩28,400 · 전주보다 4% 줄었어요 |

- 120%를 넘었는데 120% 알림을 꺼 두었으면 100%로 알린다. 80%에서 바로 120%로 오르면 120% 하나만 만든다.
- 주간 요약 금액은 만들 때의 원화 환율로 적는다(환율이 없으면 달러). 전주 기록이 온전하지 않으면 비교 문구를 뺀다.
- 연결 이름은 사용자가 붙인 이름, 없으면 `이름 없는 연결` (기능명세서 F02 규칙).

### `GET /settings/alerts` — S14
```json
{ "budget80": true, "budget100": true, "budget120": true, "spike": true, "weeklySummary": true, "channel": "IN_APP",
  "budgets": [ { "accountId": "…", "name": "운영 서버", "status": "ACTIVE", "budget": { "amount": 400000, "currency": "KRW" } },
               { "accountId": "…", "name": "이름 없는 연결", "status": "ACTIVE", "budget": null } ],
  "notes": [ "알림은 서비스 안 알림창(헤더 벨·알림 목록)으로만 보여 드려요. …" ] }
```
정하지 않았으면 조건은 모두 `true`. `budgets`는 내 연결(1시간 넘게 대기 중인 연결 제외)과 월 예산(§2-1 `PUT /budget`과 같은 값).
### `PUT /settings/alerts` → 200 (응답은 GET과 같다)
```json
{ "budget80": true, "budget100": true, "budget120": false, "spike": true, "weeklySummary": true,
  "budgets": [ { "accountId": "…", "amount": 400000, "currency": "KRW" }, { "accountId": "…", "amount": null } ] }
```
- 조건 5개는 모두 보낸다 (없으면 400 `VALIDATION_ERROR`). `budgets`는 선택이고, 보낸 연결만 바꾼다 (`amount: null`이면 예산 지움). 예산 규칙은 §2-1과 같다.
- 남의 연결이 하나라도 섞이면 **아무것도 바꾸지 않고** 404 `ACCOUNT_NOT_FOUND`.
### `GET /notifications?cursor=&limit=20` — S14-6
```json
{ "items": [ { "id": "…", "type": "BUDGET", "typeLabel": "예산", "accountId": "…", "accountName": "운영 서버",
               "message": "운영 서버의 이번 달 예상 비용이 예산의 80%를 넘었어요", "createdAt": "…", "read": false, "readAt": null,
               "deliveries": [ { "channelId": "…", "channelType": "DISCORD", "target": "https://discord.com/api/webhooks/…5678/••••",
                                 "status": "SENT", "statusLabel": "보냄", "attempts": 1, "lastError": null, "sentAt": "…" } ] } ],
  "nextCursor": "MTc2…", "unreadCount": 3, "notes": [ … ] }
```
최신순. `limit` 1~50(기본 20). 더 있으면 `nextCursor`를 그대로 `cursor`로 보낸다 (없으면 `null`). 잘못된 커서·limit은 400 `VALIDATION_ERROR`.
`deliveries`: 외부 채널별 발송 상태 `PENDING`(보내는 중·다시 보낼 예정) / `SENT` / `FAILED`(그만 보냄, `lastError`에 한국어 사유). 채널이 없거나 채널 등록 전 알림이면 `[]`.
### `GET /notifications/unread-count` — S14-2 헤더 벨
`{ "unreadCount": 3 }`. 이 조회도 조건을 확인한다.
### `POST /notifications/read-all` → 200 `{ "unreadCount": 0 }`
### `POST /notifications/{notificationId}/read` → 204
이미 읽었어도 204. 남의 알림이거나 없으면 404 `NOTIFICATION_NOT_FOUND`.

연결을 삭제하면 그 연결의 알림과 발송 기록도 함께 지운다.

## 5-1. 외부 알림 채널 — Discord 웹훅 (ADR-019)
등록한 뒤에 생긴 알림부터, 켜진 채널마다 1분 안에 보낸다. 실패하면 1분·5분·30분·2시간 간격으로 다시 보내고 5번째에 멈춘다.
웹훅 주소는 비밀 값이라 **저장 뒤에는 가린 주소(`target`)만** 돌려준다. 사용자당 5개까지.

메시지 형식: `content` 없이 **embed 1개**와 `allowed_mentions: { parse: [] }`(멘션 안 울림)로 보낸다. 링크는 본문이 아니라 embed `url`에 걸어 미리보기 카드가 붙지 않는다.
금액은 알림을 만들 때 저장한 USD 값을 보낼 때의 환율로 원화 환산한다. footer는 모두 `사용 시간 × 온디맨드 단가 기준`.

| 종류 | 제목 · 색 | 설명 | 필드 | 링크 |
|---|---|---|---|---|
| 예산 | `예산 {80\|100\|120}% 넘음` · 100·120% 빨강, 80% 주황 | `테스트서버의 이번 달 월말 예상 비용이 예산의 120%를 넘었어요.` | 연결 · 월말 예상 비용 · 예산 금액 | `/dashboard` |
| 비용 급증 | `비용 급증` · 주황 | `테스트서버의 10월 12일 비용이 평소의 2.4배예요.` | 연결 · 날짜 · 평소 대비 · 원인 서버(있을 때) | `/dashboard` |
| 주간 요약 | `주간 요약` · 파랑 | 서비스 안 알림 문구 그대로 | 연결 | `/notifications` |

V12 전에 만든 알림은 금액 기록이 없어 서비스 안 알림 문구를 설명으로 쓰고 필드는 연결만 넣는다.
서비스 안 알림창의 `message`와 알림 목록의 `deliveries` 형식은 바뀌지 않는다. 테스트 발송도 같은 embed 형식(제목 `테스트 알림`, 파랑)이다.

### `GET /settings/alerts/channels`
```json
{ "items": [ { "channelId": "…", "type": "DISCORD", "typeLabel": "Discord", "target": "https://discord.com/api/webhooks/…5678/••••",
               "enabled": true, "createdAt": "…", "updatedAt": "…" } ],
  "maxChannels": 5, "notes": [ "Discord 서버 설정 → 연동 → 웹후크에서 만든 주소를 넣어 주세요. …" ] }
```
### `POST /settings/alerts/channels` → 201 (응답은 목록의 항목 1개)
`{ "type": "DISCORD", "webhookUrl": "https://discord.com/api/webhooks/123…/abc…", "enabled": true }` (`type`·`enabled` 생략 가능)
- `https://discord.com/api/webhooks/{숫자}/{토큰}` 형식이 아니면 400 `INVALID_WEBHOOK_URL`, `type`이 DISCORD가 아니면 400 `UNSUPPORTED_CHANNEL`,
  6번째는 400 `CHANNEL_LIMIT_EXCEEDED`.
### `PUT /settings/alerts/channels/{channelId}` → 200
`{ "enabled": false }` 또는 `{ "webhookUrl": "…새 주소…", "enabled": true }` (`webhookUrl`이 없거나 빈 값이면 주소는 그대로). 남의 채널이면 404 `CHANNEL_NOT_FOUND`.
### `DELETE /settings/alerts/channels/{channelId}` → 204
그 채널의 발송 기록도 지운다 (알림은 남는다).
### `POST /settings/alerts/channels/{channelId}/test` → 200
`{ "ok": true, "reason": null }` / `{ "ok": false, "reason": "Discord 웹훅을 찾을 수 없어요. 웹훅이 삭제되지 않았는지 확인하고 주소를 다시 등록해 주세요." }`
꺼진 채널도 보낸다. 실패해도 HTTP는 200이고 `reason`에 한국어 사유를 준다.

## 6. 월간 리포트 (이슈 #37, ADR-015)
저장하지 않고 조회할 때 §2 월별·일별 비용, §2 급증일, §4 낭비 탐지 규칙으로 계산한다.

| `status` | 뜻 (S10-4) |
|---|---|
| `EMPTY` | 그 달에 사용 기록이 없음 (기록 시작 전 달 포함) |
| `FIRST_MONTH` | 기록이 시작된 첫 달. 전월과 비교하지 않고, 기록 시작일부터의 값이다 |
| `PREVIEW` | 진행 중인 이번 달 (오늘까지의 값, 달이 끝나면 확정) |
| `FINAL` | 끝난 달 |

판정 순서는 EMPTY → FIRST_MONTH → PREVIEW → FINAL.

### `GET /aws/accounts/{accountId}/reports?currency=KRW|USD` — S10-2
```json
{ "dataSource": "aws", "currency": "KRW", "availableFrom": "2026-07-10",
  "months": [ { "month": "2026-10", "status": "PREVIEW", "statusLabel": "진행 중", "usageHours": 360.00, "estimatedCost": 41600,
                "previousMonthCost": 165400, "change": -123800, "changeRate": -74.8 },
              { "month": "2026-09", "status": "FINAL", "statusLabel": "확정", … } ],
  "freshness": { … }, "notes": [ … ], "asOf": "…" }
```
기록 시작 달 ~ 이번 달(최대 14개월), 최신 달 먼저. `previousMonthCost`·`change`·`changeRate`는 전월이 기록 시작 달보다 앞이면 `null`, `changeRate`는 전월이 0이어도 `null`.
아직 수집 전이면 `availableFrom: null`, `months: []`.

### `GET /aws/accounts/{accountId}/reports/{yyyy-MM}?currency=KRW|USD` — S10
```json
{ "month": "2026-09", "status": "FINAL", "statusLabel": "확정", "from": "2026-09-01", "to": "2026-09-30",
  "summary": { …목록 한 줄과 같다… },
  "spikes": { "count": 1, "days": [ { "date": "2026-09-12", "ratio": 2.2, "causeInstanceId": "i-…", "causeName": "batch",
                                       "message": "9/12 비용 급증 · 평소의 2.2배 · batch 사용 시간이 늘었어요" } ] },
  "savings": { "status": "OK", "window": { "from": "2026-09-16T15:00:00Z", "to": "2026-09-30T15:00:00Z" }, "count": 1,
               "monthlySavings": 64200, "items": [ { "recommendationId": "i-…:OVERSIZED", "instanceId": "i-…", "name": "api",
                 "kind": "OVERSIZED", "kindLabel": "과다 사양", "action": "m5.large → t3.medium 으로 변경 검토", "monthlySavings": 64200 } ] },
  "topServers": [ { "instanceId": "i-…", "name": "api", "instanceType": "m5.large", "others": false, "usageHours": 711.00,
                    "estimatedCost": 111800, "share": 69.4 },
                  { "instanceId": null, "name": "기타", "instanceType": null, "others": true, … } ],
  "freshness": { … }, "notes": [ … ] }
```
- `from`~`to`: 그 달 1일(첫 달이면 기록 시작일) ~ 말일(이번 달이면 오늘).
- `spikes`: 그 달의 급증일 (§2 `costs/daily`의 `spike`와 같은 규칙, 앞 30일을 함께 읽어 판정).
- `savings`: **그 달 마지막 14일**(다음 달 1일 0시 KST 직전 14일, 이번 달이면 지금까지)에 §4 규칙을 적용한 결과. 지금 남아 있는 서버만 판단한다.
  네트워크 지표는 최근 14일만 모으므로 지난 달에는 유휴 판단이 빠질 수 있다 (`notes` 안내). `status`는 §4와 같다. `EMPTY` 달이면 `null`.
- `topServers`: 그 달 비용 상위 3대 + 나머지 합(`others: true`, 4대 이상일 때만). 단가를 모르는 서버는 뺀다. 비중은 단가를 아는 서버 합계 기준.
- 오류: 아직 오지 않은 달 400 `INVALID_PERIOD`, 형식이 틀린 달 400 `VALIDATION_ERROR`, 그 밖은 §2와 같다.

### `POST /demo/reports` · `POST /demo/reports/{yyyy-MM}` — 체험 모드
요청은 §3 설정. 기간의 마지막 날(`to`)을 오늘로 보고, 기간 안의 달을 같은 계산으로 보여 준다 (`from`이 든 달이 `FIRST_MONTH`).
