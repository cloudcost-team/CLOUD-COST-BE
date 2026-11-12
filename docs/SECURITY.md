# SECURITY — 절대 규칙

> 기능 요구보다 우선한다. 지키지 못할 상황이면 구현을 멈추고 사람에게 묻는다.
> 강제 수단: `T` = 테스트, `A` = ArchUnit(`architecture/ArchitectureTest`), `R` = 리뷰

| # | 규칙 | 강제 |
|---|---|---|
| S1 | 사용자에게 AWS Access Key / Secret Key를 받지 않는다. 입력란·API 필드·DB 컬럼을 만들지 않는다 | A(엔티티 필드명 검사), R |
| S2 | AssumeRole 임시 자격증명은 `StsAssumeRoleCredentialsProvider` 인스턴스 안에만 둔다. DB·캐시·로그·응답·예외 메시지에 넣지 않는다 | T(응답에 자격증명 없음), R |
| S3 | 고객 계정에는 허용 목록의 읽기 API만 호출한다: `ec2:DescribeInstances`, `cloudwatch:GetMetricStatistics`, `ec2:DescribeRegions`, `ec2:DescribeVolumes`, `ec2:DescribeAddresses`, `ec2:DescribeSnapshots`(OwnerIds=self), `elasticloadbalancing:DescribeLoadBalancers` (ADR-020) | A(허용 메서드 외 호출 금지), R |
| S4 | 고객 Role 정책에 쓰기 권한과 Action 와일드카드를 넣지 않는다. Resource `*`는 Describe·GetMetricStatistics가 리소스 단위 권한을 지원하지 않아 불가피하다. 잊힌 리전을 찾기 위해 리전 조건은 두지 않는다 (ADR-020) | T(정책 JSON·템플릿) |
| S5 | Trust Policy Principal은 우리 백엔드 Role 하나이고 `sts:ExternalId` 조건이 반드시 있다 | T |
| S6 | External ID는 연결마다 서버가 `SecureRandom`으로 만든다 (`cci-` + hex 24자). 사용자가 정하지 못한다 | T |
| S7 | 백엔드 Role ARN, DB 비밀번호, JWT Secret은 환경변수 또는 Parameter Store에 둔다. 실제 계정 ID·ARN을 코드·문서·테스트에 쓰지 않는다 (예시: 고객 `123456789012`, 우리 `111122223333`) | R |
| S8 | 모든 사용자 데이터 조회에 `owner_id` 조건을 건다. Repository는 `findById`·`findAll`을 갖지 않는다. 남의 리소스는 404 | A, T(`OwnerIsolationTest`) |
| S9 | 로그에 자격증명·JWT·Authorization 헤더를 남기지 않는다. SDK 와이어/인증 로그를 DEBUG로 켜지 않는다 | `logback-spring.xml` 마스킹, T |
| S10 | "연결 성공"은 실제 AssumeRole + 실제 DescribeInstances 응답으로만 판정한다 | R |
| S11 | 고객 리소스를 만들거나 바꾸거나 지우지 않는다 | A |
| S13 | 스택 신호(SNS → SQS)는 믿지 않는다. 연결 ID + External ID가 맞고 실제 AssumeRole이 성공해야 ACTIVE. CloudFormation 응답은 CloudFormation 응답용 S3 호스트로만 보낸다(SSRF 방지) | T(`StackCallbackTest`, `StackCallbackAdapterTest`) |
| S12 | 고객 데이터는 필요한 필드만 저장한다: 인스턴스 ID·타입·리전·AZ·상태·LaunchTime·platformDetails·테넌시·Spot 여부·Name 태그. 방치 리소스는 ID·Name 태그·리전·종류·크기·연결 여부·생성 시각·LB 요청 수만 (ADR-020). IP 주소·키페어·보안그룹·전체 태그·사용자 데이터는 저장하지 않는다 | R(`Ec2InstanceMapper`, `AwsCustomerSession.scanRegion`), T |

## 고객 Role (사용자가 빠른 생성 링크로 만든다, ADR-003)
- 템플릿 원본: `src/main/resources/cloudformation/customer-readonly-role.yaml`. Role 하나 + 우리 SNS로 알리는 Custom Resource만 만든다 (Lambda 없음).
- 권한: `scripts/customer-role-allowlist.txt`의 7개 읽기 액션 (템플릿·정책 JSON과 같은지 테스트로 확인). v1(2개 액션) 스택은 오류 대신 "권한 업데이트 필요"로 안내한다 (ADR-020)
- 신뢰 정책: Principal = `CCI_BACKEND_ROLE_ARN`, `sts:ExternalId` = 그 연결의 External ID
- 예비 경로: `roleSetup.trustPolicy`·`permissionPolicy`로 직접 만들고 `PUT .../role`로 등록
- 백엔드는 `DurationSeconds=900`, `RoleSessionName=cci-<연결 id>`로 AssumeRole한다 (고객 CloudTrail에서 추적 가능).

## 우리 계정 백엔드 Role 권한
```json
{ "Version": "2012-10-17", "Statement": [
  { "Effect": "Allow", "Action": "sts:AssumeRole", "Resource": "arn:aws:iam::*:role/CloudCost*" },
  { "Effect": "Allow", "Action": "pricing:GetProducts", "Resource": "*" },
  { "Effect": "Allow", "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage"],
    "Resource": "arn:aws:sqs:<region>:111122223333:cloudcost-insight-connections" },
  { "Effect": "Allow", "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage"],
    "Resource": "arn:aws:sqs:<region>:111122223333:cloudcost-insight-collection" } ] }
```
- 예약 수집 큐(ADR-019) 메시지에는 owner·연결 id만 넣는다 (자격증명·External ID·Role ARN 없음).
- 사용자가 등록한 Discord 웹훅 주소는 비밀 값이다: `discord.com/api/webhooks/` 형식만 받고(SSRF 방지), API 응답에는 가린 주소만,
  로그에는 토큰을 가린다 (`MaskingMessageConverter`). DB에는 AES-256-GCM으로 암호화해 저장한다 (`SecretCipher`, 키 `CCI_SECRET_KEY`는
  환경변수로만, AUTH_MODE=google이면 없을 때 시작하지 않는다). 키를 잃으면 저장된 웹훅을 못 풀므로 사용자가 다시 등록한다.
- 고객 Role 이름은 `CloudCost`로 시작해야 한다 (예: `CloudCostReadOnlyRole`). 연결 안내(`roleSetup.steps`)에 적혀 있다.
- 백엔드는 이 이름 범위 밖의 Role을 맡을 수 없어, 실수나 악용으로 다른 Role을 맡는 범위를 줄인다.

## 비용 (고객 계정)
- STS·Describe 호출: 무료. CloudWatch `GetMetricStatistics`: 월 100만 건 무료(계정 전체 공유), 초과 시 1,000건당 $0.01.
- 확장 수집(ADR-020)은 6시간에 한 번, 리전마다 Describe 5건 안팎 + LB당 GetMetricStatistics 1건(ALB는 요청 0이면 1건 더). 마지막 수집 1회의 시도 호출 수는 `account_resource_scans`에 남긴다 (누적 아님). Describe 호출은 고객 CloudTrail에 남는다 (추가 Trail을 쓰는 고객은 10만 건당 약 $2).
- 계정당 1시간에 1회만 호출하고 호출 수를 `sync_jobs.cw_request_count`에 남긴다 (ADR-002).
- `GetMetricData`, Cost Explorer는 쓰지 않는다.

## 인증 (ADR-004)
- Google 로그인: 서버가 Google ID 토큰의 서명(JWKS)·iss·aud(우리 클라이언트 ID)·exp·email_verified를 검증한다. 비밀번호를 받거나 저장하지 않는다.
- 저장: Google sub(식별자), 이메일·이름(표시용). 이메일은 식별자로 쓰지 않는다.
- 서버가 HS256 JWT(1시간)를 발급하고 `Authorization: Bearer`의 sub를 owner_id로 쓴다. `JWT_SECRET`(32바이트 이상)은 환경변수/Parameter Store.
- 인가 코드 로그인(ADR-018)의 `GOOGLE_CLIENT_SECRET`도 환경변수로만 둔다. 코드 교환 요청·Google 응답 본문은 로그·응답에 남기지 않는다.
- AUTH_MODE=google(배포): 토큰 필수, X-Owner-Id 무시. AUTH_MODE=dev는 APP_ENV=local에서만 허용되고, 토큰이 없을 때만 X-Owner-Id를 쓴다.
- 토큰·Authorization 헤더는 로그에 남기지 않는다(마스킹). 로그인 실패 사유는 자세히 알려 주지 않는다.
