# API — 엔드포인트 계약

> Base: `/api/v1`. Swagger UI: `/swagger-ui.html`, OpenAPI: `/v3/api-docs`. 이 문서가 기준이다.
> 사용자 구분: `Authorization: Bearer <accessToken>` (Google 로그인, 0절). 로컬(AUTH_MODE=dev)에서는 토큰이 없으면 `X-Owner-Id` 헤더 (없으면 `DEV_OWNER_ID`).
> 남의 리소스는 **404**다. 시각은 ISO-8601 UTC, 날짜는 Asia/Seoul 기준 `YYYY-MM-DD`.

## 0. 로그인 (ADR-004)
| 엔드포인트 | 인증 | 설명 |
|---|---|---|
| `GET /auth/config` | 없음 | `{ authMode: dev\|google, googleClientId }` |
| `POST /auth/google` | 없음 | 요청 `{ "idToken": "<Google credential>" }` → `{ accessToken, tokenType: "Bearer", expiresAt, newUser, user: { id, email, name, provider } }`. 처음이면 자동 가입 |
| `GET /me` | 필요 | 내 정보 |

- 이후 모든 API: `Authorization: Bearer <accessToken>` (1시간). AUTH_MODE=dev(로컬)에서는 토큰이 없을 때만 X-Owner-Id 허용.
- 브라우저 테스트 페이지: `/login.html` → 토큰 복사 → Swagger Authorize의 `bearerAuth`.
- 401 코드: `LOGIN_REQUIRED`(토큰 없음), `INVALID_TOKEN`(만료·위조), `INVALID_ID_TOKEN`(Google 검증 실패). 503 `GOOGLE_LOGIN_NOT_CONFIGURED`.

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
- `regionLabel`: 한국어 리전 이름. `ap-northeast-2`→서울, `ap-northeast-1`→도쿄, `us-east-1`→버지니아 북부, `us-west-2`→오레곤, 그 밖은 코드 그대로.
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

모든 응답에 `dataSource: "aws"`, `costBasis: "estimated_on_demand"`, `currency: "USD"`, `freshness`, `notes`가 있다.
```json
"freshness": { "instancesRefreshedAt": "…", "instancesFromCache": true,
               "usageRefreshedAt": "2026-10-08T03:00:00Z", "usageFromCache": true,
               "nextUsageRefreshAt": "2026-10-08T04:00:00Z", "warnings": [] }
```
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
  - 평소(`baseline`) = 그날 **직전 30일** 중 기록 있는 날(기록 시작일 `availableFrom` 이후)의 하루 추정 비용 **중앙값**. 조회 기간 앞 30일도 함께 읽어 첫날부터 판정한다.
  - 급증 = 그날 비용 ≥ 평소 × 1.5. 평소를 잰 날이 7일 미만이거나 평소가 0이면 판정하지 않는다. `ratio`는 소수 첫째 자리.
  - 원인 서버 = 그날 사용 시간이 자기 평소(같은 30일의 하루 사용 시간 중앙값)보다 가장 많이 늘어난 인스턴스. 늘어난 서버가 없으면 `cause*`가 `null`이고 문구에 서버를 쓰지 않는다.
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
  "forecast": { "monthEndCost": 312000, "costThroughYesterday": 96300, "dailyAverage": 7700,
                "basisFrom": "2026-10-28", "basisTo": "2026-11-03", "basisDays": 7, "remainingDays": 27 },
  "budget": { "amount": 400000, "enteredAmount": 400000, "enteredCurrency": "KRW", "usageRate": 78.0, "thresholdReached": null },
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

### `PUT /aws/accounts/{accountId}/budget` → 200
```json
요청 { "amount": 400000, "currency": "KRW" }   응답 { "accountId": "…", "amount": 400000, "currency": "KRW" }
```
- 입력한 통화 그대로 저장한다 (원화는 원 단위, 달러는 센트 단위로 반올림). 연결마다 하나이고 다시 보내면 바꾼다. 연결 상태와 관계없이 정할 수 있다.
- 400 `VALIDATION_ERROR`: 0 이하, 1조 초과, 숫자가 아님, `amount`·`currency` 없음 (`details`에 필드별 문구). 남의 연결은 404.
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
- `POST /demo/costs/daily`도 `days[].spike`를 준다. 체험 모드의 평소는 `from`부터 잰다 (`from` 앞은 기록이 없다).
  예: `from` 9/1, `MONTHLY_SPIKE`·`hoursPerDay` 4인 서버가 있으면 9/12가 급증일이 된다 (평소를 잰 날 11일).
- 잘못된 설정은 400 `DEMO_INVALID_SETUP` (이유는 `message`): 모르는 타입, 대수 1~10 밖, 합계 50대 초과, `to`가 오늘 이후 등. 기간 455일 초과는 `INVALID_PERIOD`.

### `POST /demo/dashboard` (`?currency=KRW|USD`) — 체험 모드 대시보드 요약
요청은 위와 같은 설정에 선택 필드 `budget`(`{ "amount": 400000, "currency": "KRW" }`)을 더한다. 응답은 §2-1과 같다.
기간의 마지막 날(`to`)을 오늘로 본다. 서버는 `from` 0시에 처음 켰다고 보므로 그 앞의 날은 0이고, `from`이 전월 1일보다 늦으면 전월 비교는 `null`이다.

### `POST /demo/waste` (`?currency=KRW|USD`) — 체험 모드 낭비 리소스
요청은 위와 같은 설정. 기간의 **마지막 14일**(아직 지나지 않은 시간 제외)을 서버별 `usagePattern`으로 판단한다. 응답은 §4와 같다.
기간이 14일보다 짧으면 `status: "INSUFFICIENT_HISTORY"`, `availableAt` = `from` + 14일.

## 4. 낭비 리소스 (이슈 #10, ADR-010)
### `GET /aws/accounts/{accountId}/waste?currency=KRW|USD`
최근 14일(`window`, 정시 기준) EC2 사용률(#24, ADR-009)로 과다 사양·유휴 서버를 찾는다. **조언만 한다** (중지·변경 버튼 없음).
S07 목록과 S07-2 추천 상세는 이 응답 하나로 그린다 (상세 API 없음).
```json
{ "dataSource": "aws", "costBasis": "estimated_on_demand", "currency": "KRW", "exchangeRate": { … },
  "status": "OK", "availableAt": null,
  "window": { "from": "2026-10-21T02:00:00Z", "to": "2026-11-04T02:00:00Z" },
  "totalMonthlySavings": 132000, "forecastMonthEndCost": 312000, "savingsRateOfForecast": 42.3, "findingCount": 3, "counts": { "oversized": 2, "idle": 1 }, "judgedCount": 5,
  "items": [ {
      "recommendationId": "i-0abc:OVERSIZED", "instanceId": "i-0abc", "name": "api-server", "instanceType": "m5.large",
      "state": "running", "kind": "OVERSIZED", "kindLabel": "과다 사양",
      "evidence": "최근 14일 CPU 평균 6% (P95 9%)", "action": "m5.large → t3.medium 으로 변경 검토",
      "metrics": { "cpuAvg": 6.12, "cpuP95": 9.00, "cpuMax": 23.00, "networkBytes": 3523215360, "cpuHours": 336 },
      "monthlyHours": 730.00, "estimatedMonthlyCost": 118300, "estimatedMonthlySavings": 65700,
      "comparison": {
        "current":     { "instanceType": "m5.large",  "vcpu": 2, "memoryGib": 8, "hourlyCost": 162.1, "monthlyCostAt730h": 118300 },
        "recommended": { "instanceType": "t3.medium", "vcpu": 2, "memoryGib": 4, "hourlyCost": 72.1,  "monthlyCostAt730h": 52600 } },
      "cautions": [ "메모리 사용률은 CloudWatch 기본 지표에 없어 판단에 넣지 않았어요. …", … ],
      "consoleSteps": [ "EC2 → 인스턴스에서 api-server을(를) 중지해요 …", … ] } ],
  "skipped": [ { "instanceId": "i-0gpu", "name": "gpu-test", "reason": "CPU를 적게 쓰지만 GPU 타입이라 …" } ],
  "freshness": { … }, "notes": [ "…미연결 볼륨 · 미사용 고정 IP · 스냅샷 · 로드밸런서는 지금 연결 권한으로는 찾을 수 없어요." ], "asOf": "…" }
```
- `status`: `OK`(추천 있음) · `NONE_FOUND`(판단했지만 줄일 서버 없음, S07-3 A) · `INSUFFICIENT_HISTORY`(기록 시작일부터 14일 미만, S07-3 B. `availableAt`부터 판단).
  연결 오류(S07-3 D)는 §2처럼 저장된 기록으로 판단하고 `freshness.warnings`에 원인이 붙는다.
- 규칙 (최근 14일 동안 **24시간 이상** 켜져 있던 서버만 판단, `judgedCount`):
  - **유휴** `IDLE`: CPU 평균 < 1% 그리고 네트워크 입출력 하루 평균 < 5MB → 중지 검토. 절감액 = 월 추정 비용 전부. 네트워크를 아직 모르면 유휴로 보지 않는다.
  - **과다 사양** `OVERSIZED`: CPU P95 < 10% → t3 패밀리에서 메모리 절반 이하·vCPU 이하 중 가장 큰 타입으로 변경 검토
    (예: m5.large → t3.medium, m5.xlarge → t3.large, r5.4xlarge → t3.2xlarge).
    절감액 = 월 비용 차이. 사양을 모르는 타입·GPU 타입·더 작은 타입이 없거나 더 싸지 않거나 단가를 모르면 추천하지 않고 `skipped`에 이유를 적는다.
- 월 추정 비용 = 최근 14일 사용 시간을 한 달(730시간)로 환산(최대 730) × 온디맨드 단가. 사양 비교의 `monthlyCostAt730h`는 730시간 고정.
- `items`는 절감액 큰 순. `recommendationId`(`{instanceId}:{kind}`)는 시뮬레이터·벤치마킹에 그대로 보낸다.
- 지금 꺼져 있어도 최근 14일 안에 켜져 있었으면 판단한다. 단, **지금 중지돼 있고 최근 3일 동안 한 번도 안 켜진 서버**는 이미 비용이 나지 않으므로
  판단하지 않고 `skipped`에 이유를 적는다 (밤·주말에 꺼지는 서버가 조회 시각에 따라 빠지지 않게 "지금 중지"만으로는 빼지 않는다).
  삭제된 인스턴스(`present=false`)는 빼고, 14일 동안 한 번도 안 켜진 서버는 목록에 넣지 않는다.
- `forecastMonthEndCost`·`savingsRateOfForecast`(S07 "이번 달 예상 비용 대비 %"): §2-1 월말 예상과 같은 값, 절감 예상 ÷ 월말 예상(%). 월말 예상이 없으면 `null`.
- 오류: §2와 같다 (404 남의 연결, 409 대기·끊긴 연결, 429/502 AWS).


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
- `trafficGrowthPercent`는 0~100 정수, 없으면 0. `recommendationIds`가 없거나 비면 추천 없이 계산한다 (적용 전 = 적용 후). 중복 id는 한 번만 센다.
- 예산이 있으면 `budgetRate`(예산 대비 %)와 `withinBudget`(적용 후 ≤ 예산), 없으면 `budget`·`budgetRate`·`withinBudget`이 `null`.
- 월말 예상이 없으면(끝난 날 기록 없음·단가를 아는 서버 없음) `status: "NO_FORECAST"`이고 금액 필드가 `null`, `notes`에 안내.
- 오류: 400 `UNKNOWN_RECOMMENDATION`(지금 추천 목록에 없는 id, `message`에 id), 400 `VALIDATION_ERROR`(증가율 범위 밖, id 200개 초과), 그 밖은 §2와 같다.

### `POST /demo/simulations?currency=KRW|USD` — 체험 모드
§3 설정에 `recommendationIds`·`trafficGrowthPercent`·`budget`(모두 선택)을 더한다. 추천 id는 같은 설정의 `POST /demo/waste` 응답에서 나온 값이다.
기간의 마지막 날을 오늘로 보고 같은 계산을 한다.
