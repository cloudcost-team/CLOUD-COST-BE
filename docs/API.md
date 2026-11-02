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
- `cwRequestCount`는 재시도를 포함한 실제 호출 수다.

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
`days: [ { "date": "2026-10-07", "usageHours": 10.00, "estimatedCost": 0.42, "instances": [ … ] } ]` — 사용이 없는 날도 포함.
### `GET /aws/accounts/{accountId}/costs/monthly?from=YYYY-MM&to=YYYY-MM` (기본: 최근 3개월)
`months: [ { "month": "2026-10", "usageHours": …, "estimatedCost": …, "instances": [ … ] } ]` — 일별 합산.

**단가를 찾지 못한 인스턴스**: `estimatedCost: null`, `note: "해당 인스턴스의 온디맨드 단가를 조회할 수 없습니다."`.
기간 합계는 단가가 있는 인스턴스만 더하고, `notes`에 빠진 인스턴스 수와 시간을 적는다.
조회 기간은 최대 455일이다.

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
`schedules`(`ALWAYS` 24시간 · `WEEKDAY_OFFICE` 평일 09~19시 · `DAILY_HOURS` 매일 09시부터 N시간), 한도(`maxServers` 20, `maxCountPerServer` 10, `maxInstances` 50).

### 비용 조회: `POST /demo/instances` · `/demo/costs` · `/demo/costs/daily` · `/demo/costs/monthly` (`?currency=KRW|USD`)
```json
{ "from": "2026-10-01", "to": "2026-10-31",
  "servers": [ { "name": "web", "instanceType": "m5.large", "os": "LINUX", "count": 2, "schedule": "ALWAYS" },
               { "name": "gpu", "instanceType": "g4dn.xlarge", "os": "LINUX", "count": 1, "schedule": "DAILY_HOURS", "hoursPerDay": 6 } ] }
```
- 응답은 §2의 실제 경로(`GET /aws/accounts/{accountId}/…`)와 같은 형식이다. 기간은 기본값 없이 요청의 `from`~`to`를 쓰고, 월별은 그 기간을 월로 나눈다.
- 다른 점: `dataSource: "demo"`, `accountId`는 위 고정값, `notes` 맨 앞에 체험 데이터·예시 단가 안내, `freshness`의 시각은 모두 `null`.
- `from` 0시에 모든 서버를 처음 켰다고 보고, 지난 시간까지만 계산한다(현재 진행 중인 시간 제외). 인스턴스 이름은 대수가 2대 이상이면 `web-1`, `web-2`…
- 단가는 서울 리전 온디맨드 단가를 고정값으로 쓴다 (Price List를 부르지 않음). 금액 계산은 실제 경로와 같은 domain 로직이다.
- 잘못된 설정은 400 `DEMO_INVALID_SETUP` (이유는 `message`): 모르는 타입, 대수 1~10 밖, 합계 50대 초과, `to`가 오늘 이후 등. 기간 455일 초과는 `INVALID_PERIOD`.
