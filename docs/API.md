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
| 400 | `VALIDATION_ERROR`, `INVALID_ROLE_ARN`, `INVALID_REGION`, `INVALID_PERIOD` | 형식 오류 (accountId가 UUID가 아님 포함) |
| 401 | `UNAUTHORIZED` | X-Owner-Id 형식 오류 |
| 404 | `ACCOUNT_NOT_FOUND` | 없거나 남의 연결 |
| 409 | `ACCOUNT_NOT_ACTIVE`, `ACCOUNT_DISCONNECTED`, `ACCOUNT_ALREADY_CONNECTED`, `ROLE_ACCOUNT_CHANGED` | 상태 충돌 |
| 422 | `AWS_ASSUME_ROLE_FAILED`, `AWS_PERMISSION_DENIED` | 고객 Role 설정 문제 |
| 429 | `AWS_THROTTLED` | |
| 502 | `AWS_UNAVAILABLE`, `AWS_ERROR` | AWS 연결 실패 |
| 503 | `BACKEND_AWS_NOT_CONFIGURED` | 서버 AWS 자격증명 없음 (사용자 잘못 아님) |

## 1. AWS 연결 (ADR-003, 예비: ADR-001)
### `POST /aws/accounts` → 201
요청(선택): `{ "region": "ap-northeast-2" }`
```json
{ "account": { "accountId": "…", "status": "PENDING", "region": "ap-northeast-2", "externalId": "cci-0123456789abcdef01234567",
               "roleArn": null, "awsAccountId": null, "lastError": null, "verifiedAt": null, "stackId": null, "disconnectedAt": null, … },
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
### `GET /aws/accounts` · `GET /aws/accounts/{accountId}`
목록 / 상세(+ `roleSetup`).
### `GET /aws/accounts/{accountId}/sync-jobs?limit=20`
`[ { jobId, trigger, status, instancesRefreshed, usageRefreshed, ec2RequestCount, cwRequestCount, instanceCount, usageRows, error, warning, startedAt, finishedAt } ]`

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
`ACTIVE`가 아니면 409.

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
