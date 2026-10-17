# AWS SETUP GUIDE — 로컬에서 실제 AWS 계정으로 테스트하기

> 사람용 문서다. 배포 없이 내 PC의 Spring Boot에서 실제 AWS를 호출한다.
> 실제 계정 ID, ARN, External ID, 액세스 키를 리포지토리에 커밋하지 않는다.
> 1~3절은 **관리자가 서비스 준비 때 한 번만** 한다. 사용자(고객)는 4절만 한다.

## 0. 개념
| 이름 | 무엇 |
|---|---|
| 계정 A (우리 계정) | CloudCost Insight 백엔드 계정. 백엔드 Role, 템플릿 S3, 신호용 SNS·SQS |
| 계정 B (고객 계정) | EC2 사용량을 볼 계정. 링크로 Read Only Role `CloudCostReadOnlyRole`을 만든다 |

**계정이 하나뿐이어도 된다** (A = B).
```
고객이 링크로 스택 생성 → [B] Read Only Role 생성 → [A] SNS 토픽에 "생성 완료" → [A] SQS 큐
내 PC 서버가 SQS를 꺼내 봄 → STS AssumeRole(+External ID)로 검증 → ACTIVE → CloudFormation에 "완료" 응답
```
액세스 키는 내 PC의 `~/.aws`에만 둔다. 화면·DB·리포지토리에는 어떤 키도 넣지 않는다.
리전은 모두 `ap-northeast-2`(서울)로 맞춘다. **SNS 토픽은 고객이 스택을 만드는 리전과 같아야 한다.**

## 1. [A] 개발자 IAM 사용자와 CLI 프로필
1. IAM → 사용자 → 사용자 생성: `cci-dev` (콘솔 접근 없음, 권한 없음)
2. `cci-dev` → 보안 자격 증명 → 액세스 키 만들기(CLI) → 터미널에서 `aws configure --profile cci-dev` (리전 `ap-northeast-2`)
3. 확인: `aws sts get-caller-identity --profile cci-dev`

## 2. [A] 연결용 리소스 3개 (S3 · SNS · SQS)

### 2.1 S3: 템플릿 보관
1. S3 → 버킷 만들기: 이름 예 `cci-templates-<A>`, 리전 서울
2. 이 버킷만 **퍼블릭 액세스 차단 → 버킷 정책 관련 2개 해제** (템플릿 파일 하나를 고객 CloudFormation이 읽어야 한다. 비밀값은 없다)
3. Swagger `GET /api/v1/aws/cloudformation-template`로 내려받은 `cloudcost-insight-readonly-role.yaml`을
   `customer-readonly-role.yaml` 이름으로 업로드
4. 버킷 정책 (이 파일 하나만 공개 읽기):
   ```json
   { "Version": "2012-10-17", "Statement": [ { "Effect": "Allow", "Principal": "*", "Action": "s3:GetObject",
     "Resource": "arn:aws:s3:::cci-templates-<A>/customer-readonly-role.yaml" } ] }
   ```
5. 객체 URL 복사 → `CCI_TEMPLATE_URL` (예: `https://cci-templates-<A>.s3.ap-northeast-2.amazonaws.com/customer-readonly-role.yaml`)
6. 템플릿을 고치면 다시 업로드한다 (원본: `src/main/resources/cloudformation/customer-readonly-role.yaml`)

### 2.2 SQS: 서버가 신호를 꺼내 가는 우편함
1. SQS → 대기열 생성 → 표준, 이름 `cloudcost-insight-connections`
2. 표시 제한 시간(Visibility timeout) **3분**, 메시지 보존 기간 **4일**
3. URL 복사 → `CCI_CALLBACK_QUEUE_URL` (예: `https://sqs.ap-northeast-2.amazonaws.com/<A>/cloudcost-insight-connections`)

### 2.3 SNS: 고객 스택이 신호를 보내는 창구
1. SNS → 주제 생성 → 표준, 이름 `cloudcost-insight-connections`
2. 액세스 정책 → 고급 → 아래로 바꾼다 (다른 AWS 계정의 CloudFormation이 보낼 수 있어야 한다):
   ```json
   { "Version": "2012-10-17", "Statement": [ { "Sid": "CustomerStacksPublish", "Effect": "Allow",
     "Principal": { "AWS": "*" }, "Action": "sns:Publish",
     "Resource": "arn:aws:sns:ap-northeast-2:<A>:cloudcost-insight-connections" } ] }
   ```
   누구나 메시지를 보낼 수 있지만 서버는 메시지를 믿지 않는다. 연결 ID·External ID가 맞고 실제 AssumeRole이 성공해야만 연결된다.
3. ARN 복사 → `CCI_CALLBACK_TOPIC_ARN`
4. 주제 → **구독 생성** → 프로토콜 Amazon SQS → 2.2의 큐 선택. (콘솔에서 만들면 큐 정책이 자동으로 추가된다.
   안 되면 큐 액세스 정책에 `sns.amazonaws.com`의 `sqs:SendMessage`를 `aws:SourceArn = 토픽 ARN` 조건으로 허용한다)

## 3. [A] 백엔드 Role `CloudCostInsightBackendRole`
1. IAM → 역할 생성 → 사용자 지정 신뢰 정책:
   ```json
   { "Version": "2012-10-17", "Statement": [
     { "Effect": "Allow", "Principal": { "AWS": "arn:aws:iam::<A>:user/cci-dev" }, "Action": "sts:AssumeRole" } ] }
   ```
2. 이름 `CloudCostInsightBackendRole`로 만든 뒤 인라인 정책:
   ```json
   { "Version": "2012-10-17", "Statement": [
     { "Effect": "Allow", "Action": "sts:AssumeRole", "Resource": "arn:aws:iam::*:role/CloudCost*" },
     { "Effect": "Allow", "Action": "pricing:GetProducts", "Resource": "*" },
     { "Effect": "Allow", "Action": ["sqs:ReceiveMessage", "sqs:DeleteMessage"],
       "Resource": "arn:aws:sqs:ap-northeast-2:<A>:cloudcost-insight-connections" } ] }
   ```
3. `cci-dev` 사용자에 인라인 정책: `sts:AssumeRole` on `arn:aws:iam::<A>:role/CloudCostInsightBackendRole`
4. `~/.aws/config`에 추가:
   ```ini
   [profile cci-backend]
   role_arn = arn:aws:iam::<A>:role/CloudCostInsightBackendRole
   source_profile = cci-dev
   region = ap-northeast-2
   ```
5. 확인: `aws sts get-caller-identity --profile cci-backend` → `assumed-role/CloudCostInsightBackendRole/...`

> 백엔드 Role을 지우고 같은 이름으로 다시 만들면 고객 Role의 신뢰 정책이 깨진다 (IAM이 내부 ID로 저장). 그때는 고객이 스택을 다시 만든다.

### 로컬 실행
```bash
cd BE
docker compose up -d db
export AWS_PROFILE=cci-backend
export CCI_BACKEND_ROLE_ARN=arn:aws:iam::<A>:role/CloudCostInsightBackendRole
export CCI_TEMPLATE_URL=https://cci-templates-<A>.s3.ap-northeast-2.amazonaws.com/customer-readonly-role.yaml
export CCI_CALLBACK_TOPIC_ARN=arn:aws:sns:ap-northeast-2:<A>:cloudcost-insight-connections
export CCI_CALLBACK_QUEUE_URL=https://sqs.ap-northeast-2.amazonaws.com/<A>/cloudcost-insight-connections
./gradlew bootRun
```
→ http://localhost:8080/swagger-ui.html. `CCI_CALLBACK_QUEUE_URL`이 있으면 신호 수신기가 자동으로 켜진다.

## 4. [B] 사용자: 연결하기 (링크 한 번)
1. Swagger `POST /api/v1/aws/accounts` → 응답의 `roleSetup.quickCreateUrl`을 브라우저에서 연다 (계정 B로 로그인)
2. 값이 모두 채워진 스택 생성 화면이 열린다. 맨 아래 **"AWS CloudFormation에서 사용자 지정 이름으로 IAM 리소스를 생성할 수 있음을 승인합니다"** 체크 → **스택 생성**
3. 1~2분 기다린다. 스택이 `CREATE_COMPLETE`가 되면 연결도 끝난 것이다.
4. Swagger `GET /api/v1/aws/accounts/{accountId}` → `status: ACTIVE` 확인
5. `GET .../instances` → `GET .../costs/daily` → `GET .../sync-jobs`(cwRequestCount 확인)

**연결 끊기**: 계정 B → CloudFormation → `CloudCostInsight-<연결 ID 앞 8자리>` 스택 삭제 → Role이 지워지고 연결이 자동으로 `DISCONNECTED`가 된다.

**예비 방법** (1~2절 준비 전): 응답의 `trustPolicy`·`permissionPolicy`로 IAM에서 `CloudCostReadOnlyRole`을 직접 만들고
`PUT /api/v1/aws/accounts/{accountId}/role`로 ARN을 등록한다.

## 5. 오류와 조치
| 증상 | 원인 | 조치 |
|---|---|---|
| 응답 `quickCreateUrl: null` + warnings | 서버에 `CCI_TEMPLATE_URL`·`CCI_CALLBACK_TOPIC_ARN`·`CCI_BACKEND_ROLE_ARN` 없음 | 3절 로컬 실행 환경변수 |
| 스택 생성 화면에서 템플릿을 못 읽음 | S3 객체가 공개 읽기가 아님 | 2.1의 버킷 정책 |
| 스택이 `ConnectionNotice`에서 오래 멈춤 | 서버가 꺼져 있거나 `CCI_CALLBACK_QUEUE_URL` 없음, SNS→SQS 구독 없음 | 서버 실행, 2.3-4 확인. 1시간이 지나면 스택이 실패하므로 삭제 후 링크를 다시 연다 |
| 스택이 FAILED + "Role을 확인하지 못했습니다" | Principal(백엔드 Role ARN) 불일치, 백엔드 Role 권한 누락 | 3절 확인 후 스택 삭제·재생성 |
| 스택이 FAILED + "연결을 찾을 수 없습니다" | 다른 연결의 링크를 재사용 | 연결을 새로 시작하고 새 링크 사용 |
| `CloudCostReadOnlyRole-… already exists` | 같은 연결로 만든 스택이 이미 있음 | 새 연결(`POST /accounts`)로 받은 링크를 쓰거나 이전 스택 삭제 후 다시 |
| 503 `BACKEND_AWS_NOT_CONFIGURED` | 서버 AWS 자격증명 없음 | `AWS_PROFILE=cci-backend` |
| `freshness.warnings`에 CloudWatch 권한 없음 | 스택 정책이 수정됨 | 스택 재생성. 1시간 뒤 자동 재시도 |
| 사용 시간이 0 | 인스턴스가 꺼져 있었거나 막 만들어짐 | CloudWatch 데이터는 실행 몇 분 뒤 생긴다. 1시간 캐시 뒤 다시 조회 |

## 6. 비용
- 고객 계정: STS·DescribeInstances·CloudFormation 무료. CloudWatch GetMetricStatistics 월 100만 건 무료(계정 전체 공유), 계정당 1시간 1회만 호출.
- 우리 계정: Price List 무료. S3(템플릿 1개), SNS·SQS(연결 때만 몇 건) 모두 무료 범위. 로컬 실행이라 서버 비용 없음.
- 테스트용 EC2를 만들었다면 끝나고 직접 중지·종료한다 (CloudCost Insight는 아무것도 바꾸지 않는다).

## 7. 정리
계정 B의 `CloudCostInsight-…` 스택을 지우면 접근 권한이 완전히 회수된다. 개발이 끝나면 `cci-dev` 액세스 키를 비활성화한다.
