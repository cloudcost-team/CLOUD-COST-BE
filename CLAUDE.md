# CloudCost Insight — 에이전트 작업 규칙

AWS 비용 분석 서비스 (단국대 캡스톤 MVP). 사용자가 자기 AWS 계정에 **읽기 전용 IAM Role**을 만들고
Role ARN만 입력하면, 백엔드(Java/Spring Boot)가 STS AssumeRole(+연결별 External ID)로 **EC2·CloudWatch만** 읽어
PostgreSQL에 저장하고, 기존 와이어프레임 화면에 실제 데이터로 보여준다.
비용은 청구서가 아니라 **사용 시간 × 온디맨드 단가**로 계산한 추정치다 (Cost Explorer·Compute Optimizer·ELB는 쓰지 않는다).
**CloudCost Insight는 READ ONLY 서비스다. 고객 AWS 리소스를 만들거나 바꾸거나 지우지 않는다.**

## 0. 절대 규칙 — 위반하면 즉시 멈춘다 (상세·근거: docs/SECURITY.md)
1. 사용자에게 AWS Access Key / Secret Key를 받는 화면·API·DB 컬럼을 만들지 않는다.
2. AssumeRole로 받은 임시 자격증명은 메모리에서만 쓴다(`StsAssumeRoleCredentialsProvider` 인스턴스 안에서만 보관).
   DB·캐시·파일·SQS·로그·API 응답·예외 메시지에 넣지 않는다.
3. 고객 계정에는 아래 **허용 목록 2개 액션**만 호출한다. 목록 밖의 호출,
   특히 create/delete/modify/put/update/stop/terminate/release/detach 계열은 절대 쓰지 않는다.
   허용 목록의 기준은 `scripts/customer-role-allowlist.txt`이며 이 문서 §10과 항상 같아야 한다.
   - `ec2:DescribeInstances`
   - `cloudwatch:GetMetricStatistics`
4. 고객 Role 정책에 쓰기 권한과 **Action 와일드카드**(`ec2:*`, `Describe*`)를 넣지 않는다. 권한 추가는 사람 승인 + ADR이 먼저다.
   (`Resource: "*"`는 이 두 액션이 리소스 단위 권한을 지원하지 않아 불가피하다. 대신 `aws:RequestedRegion` 조건으로 리전을 제한한다.)
5. Trust Policy는 우리 백엔드 Role만 Principal로 두고 `sts:ExternalId` 조건을 반드시 건다.
   External ID는 연결마다 서버가 `SecureRandom`으로 생성한다. 사용자가 정하지 못한다.
6. 사용자 데이터 조회·수정에는 항상 `owner_id = JWT sub` 조건을 건다. 남의 리소스 요청은 404로 응답한다.
7. 비밀값과 환경별 값(백엔드 Role ARN, DB 비밀번호, Webhook, Cognito 설정)은 환경변수 또는 Parameter Store에 둔다.
   코드·문서·테스트에 실제 계정 ID·ARN을 쓰지 않는다. 예시는 `123456789012`(고객), `111122223333`(우리)만 쓴다.
8. 로그에 자격증명·JWT·Authorization 헤더를 남기지 않는다. AWS SDK 와이어/요청 로그
   (`software.amazon.awssdk.request`, `org.apache.http.wire`, `software.amazon.awssdk.auth`)를 DEBUG 이하로 켜지 않는다.
9. "연결 성공"을 Mock으로 만들지 않는다. 실제 AssumeRole과 실제 AWS 응답이 있어야 성공이다.
10. 고객 계정 데이터는 필요한 필드만 저장한다. 인스턴스 ID·타입·상태·LaunchTime·AZ·OS(platformDetails)·테넌시·수명주기(spot 여부)·Name 태그 외에
    IP, 키페어 이름, 보안그룹, 태그 전체, 사용자 데이터는 **DB에 저장하지 않는다**(필요하면 요청 시점에 조회만 한다).

## 1. 세션 루틴
- **시작**: `plans/PROGRESS.md` → `plans/ROADMAP.md`(현재 Phase) → `plans/phase-XX-*.md` 순서로 읽는다. 명령: `/phase-start N`
- **한 번에 한 Phase만** 한다. 계획 파일 밖의 일은 하지 말고 PROGRESS.md의 "다음 후보"에 적는다.
- **완료라고 말하기 전과 커밋 전**에 `scripts/verify.sh`를 통과시킨다. 명령: `/verify`.
  `git commit`은 hook(`.claude/settings.json`)이 verify를 강제한다. 검사를 끄거나 우회하지 않는다.
- **종료**: `plans/PROGRESS.md` 맨 위에 세션 기록을 추가하고 ROADMAP 체크박스를 갱신한다. 명령: `/session-end`
- 사람이 AWS 콘솔에서 할 일이 생기면 `docs/AWS_SETUP_GUIDE.md`의 해당 절을 알려 주고 멈춘다.

## 2. 문서 지도 (필요할 때만 열기)
| 파일 | 내용 | 언제 |
|---|---|---|
| `docs/SECURITY.md` | 절대 규칙, 고객/백엔드 Role 정책, JWT, 로그 | AWS·인증·로그 코드를 만질 때 |
| `docs/ARCHITECTURE.md` | 헥사고날 구조, 포트/어댑터, sync·SQS 흐름, 환경변수 | 새 모듈을 만들 때 |
| `docs/DATA_MODEL.md` | 테이블·제약·인덱스, Phase별 마이그레이션 | DB를 만질 때 |
| `docs/API.md` | 엔드포인트 계약, 요청/응답 예시, 에러 코드 | API·프론트 연동 시 |
| `docs/AWS_SETUP_GUIDE.md` | 사람용 AWS 콘솔 클릭 가이드, 비용, 문제 해결 | 실제 계정 테스트 시 |
| `docs/decisions/` | ADR (왜 이렇게 했는지) | 설계를 바꾸고 싶을 때 |
| `../../캡스톤/Demo/docs/reference/cloudcost-wireframe.html` | **UI 기준 (수정본)**. Mock 데이터 원본 (이전 Python 프로토타입 폴더) | 화면 작업 시 |
| `../../캡스톤/Demo/docs/PRODUCT.md` | 이전 프로토타입의 기능 스펙 (Cost Explorer 기준이라 참고만) | 기능 확장 시 |

## 3. 저장소 구조 (이 BE 폴더가 Gradle 프로젝트 루트)
패키지 루트는 `com.cloudcost`다(바꾸면 이 절과 ArchUnit 테스트를 같이 고친다).
```
src/main/java/com/cloudcost/domain/                 순수 Java: ARN·External ID, 사용 시간 계산, 일별·월별 집계, 비용 추정, 캐시 정책
src/main/java/com/cloudcost/application/            유스케이스(AwsAccountService, Ec2DataRefresher, Ec2UsageQueryService, PriceService) + port/
src/main/java/com/cloudcost/adapter/awscustomer/    고객 계정 호출 (sts AssumeRole, ec2 DescribeInstances, cloudwatch GetMetricStatistics만)
src/main/java/com/cloudcost/adapter/awsinternal/    우리 계정 (Price List, 스택 신호 SQS 수신, CloudFormation 응답)
src/main/java/com/cloudcost/adapter/persistence/    JPA 엔티티 + Repository 어댑터
src/main/java/com/cloudcost/entrypoint/             api(Spring MVC, Swagger) · worker(스택 신호 SQS 폴링) · config(조립) · logging(마스킹)
src/main/resources/cloudformation/                  고객용 CloudFormation 템플릿 원본 (빠른 생성 링크, ADR-003)
src/main/resources/db/migration/                    Flyway (V{n}__설명.sql)
src/test/java/                                      domain · application(H2 + fake AWS) · adapter(Mockito) · api(owner 격리) · architecture(ArchUnit)
```
연결은 CloudFormation 빠른 생성 링크 → 고객 스택이 우리 SNS로 신호 → SQS 폴링 → 실제 AssumeRole 검증으로 자동 완료한다 (docs/decisions/003).
아직 없는 것: adapter/demo, 사용량 예약 수집(dispatcher), JWT 로그인, 프론트 연동.
수집은 **조회 시점**에 한다: 인스턴스 10분, CloudWatch 계정당 1시간 1회 캐시 (docs/decisions/002).

## 4. 아키텍처 규칙 (헥사고날)
- `domain/`은 JDK 표준 라이브러리만 쓴다. AWS SDK·Spring·JPA(jakarta.persistence)·Jackson을 import하지 않는다.
- `application/`은 포트(interface)에만 의존한다. AWS SDK·Spring Web·JPA를 import하지 않는다(`@Service`, `@Transactional` 정도만 허용).
- AWS SDK for Java v2는 `adapter/` 안에서만 쓴다. `Ec2Client`, `CloudWatchClient`, `StsClient`, `PricingClient` 같은 **동기 클라이언트**만 쓰고,
  클라이언트는 연결(AssumeRole)마다 만들어 쓰고 요청이 끝나면 닫는다(`try-with-resources`).
- 위 의존 규칙은 ArchUnit 테스트(`architecture` 패키지)로 강제하며 `scripts/verify.sh`가 실행한다.
- 리전: Price List 클라이언트는 `us-east-1`, 나머지(EC2·CloudWatch)는 연결에 저장된 리전(기본 `ap-northeast-2`)을 쓴다.
- **고객 계정 호출은 `adapter/awscustomer`, 우리 계정 호출은 `adapter/awsinternal`로 패키지를 분리**하고 서로 import하지 않는다.
  인스턴스 타입 사양(vCPU·메모리·GPU)은 고객 계정이 아니라 **우리 계정의 `DescribeInstanceTypes`**로 조회해 고객 권한을 늘리지 않는다.
- 단위 테스트는 실제 AWS를 부르지 않는다. 가짜 어댑터(`src/test/.../fakes`)나 Mockito로 포트/클라이언트를 대체한다.
- Repository 메서드는 첫 인자로 `ownerId`를 받는다. owner 없이 id만으로 조회하는 메서드를 만들지 않는다.
- worker는 SQS 메시지가 아니라 DB의 sync_job 행에서 owner_id를 읽는다.

## 5. 데이터 원칙
- AWS 연결과 sync가 성공한 사용자에게는 실제 데이터를 보여 준다. 연결이 없거나 둘러보기 모드면 `/api/v1/demo/*` 예시 데이터를 보여 준다.
  모든 응답에 `data_source`(`aws` | `csv` | `demo`)를 넣는다.
- 프론트는 계산하지 않는다. 예측, 급증일, 낭비, 시뮬레이션, 벤치마크 계산은 모두 백엔드 domain에서 한다.
- **사용 시간**은 CloudWatch `GetMetricStatistics`(period 3600)의 데이터 포인트가 있는 시간 수로 센다.
  응답당 최대 1440개이므로 30일 단위로 나눠 호출하고, 조회 기간은 최대 455일이다. `GetMetricData`(유료)는 쓰지 않는다.
- **비용**은 `사용 시간 × 온디맨드 단가`의 추정치다. 단가는 우리 계정의 Price List에서 가져오고,
  인스턴스의 OS(`platformDetails`)·테넌시·수명주기(spot)에 맞는 필터를 쓴다.
  단가를 못 구하면 0이 아니라 `null`과 사유를 반환한다. 응답에 `cost_basis`(`estimated_on_demand`)를 넣고,
  RI·Savings Plans·Spot 할인과 EBS·데이터 전송 비용은 포함하지 않는다고 명시한다.
- **실시간**은 `DescribeInstances`(상태, LaunchTime)와 최근 5분 단위 CloudWatch 지표로 계산한다.
  LaunchTime은 stop 후 start하면 리셋되므로 업타임은 "현재 실행 구간" 기준임을 응답에 명시한다.
- **월별·과거**는 연결 직후 CloudWatch로 소급 조회(backfill)하고, 이후 sync가 일별 스냅샷을 DB에 쌓아 그 기록을 우선 사용한다.
  연결 시점에 이미 삭제된 인스턴스는 조회할 수 없다. 이 한계를 화면과 응답 `note`에 표시한다.
- CloudWatch 호출은 무료 한도(월 100만 건, **고객 계정** 기준) 안에서 쓴다. sync 1회의 호출 수를 `sync_jobs.cw_request_count`에 기록하고,
  연결 테스트는 `DescribeInstances` 1건으로 제한한다.
- 금액은 `BigDecimal`/`NUMERIC`, 통화는 USD로 다룬다. 날짜는 UTC로 저장하고 화면에는 KST로 보여 준다. 월 환산은 730시간 기준이다.

## 6. UI 원칙
- `docs/reference/cloudcost-wireframe.html`(수정본)이 디자인 기준이다. 색 토큰·레이아웃·문구를 유지하고 다시 디자인하지 않는다.
- 메뉴: 대시보드 / 절감(낭비 리소스, 비용 시뮬레이터) / 비교(팀 벤치마킹) / 월간 리포트 / 설정(데이터 연결, 알림 설정).
  로그인 화면과 둘러보기 모드, 헤더의 알림 벨도 유지한다.
- 비용 금액에는 "추정" 라벨을 붙인다. 청구서 금액처럼 보이게 만들지 않는다.
- 오류 문구는 원인이 드러나게 한국어로 쓴다. 예: `AccessDenied: cloudwatch:GetMetricStatistics 권한이 없습니다.` "연결 실패"만 쓰지 않는다.
- 추천은 조언까지만 한다. "삭제/중지" 버튼이나 AWS 변경 기능을 만들지 않는다.

## 7. 명령어 (바뀌면 이 절을 고친다)
```bash
./gradlew test               # 단위·통합 테스트 (H2 + fake AWS, 실제 AWS 호출 없음)
docker compose up -d db      # 로컬 PostgreSQL (Compose 프로젝트 이름 cloudcost-insight)
./gradlew bootRun            # 포트 8080, PostgreSQL + Flyway → http://localhost:8080/swagger-ui.html
```
실제 AWS로 실행하려면 `CCI_BACKEND_ROLE_ARN`, `AWS_PROFILE`이 필요하다 (docs/AWS_SETUP_GUIDE.md).
배포(EC2 + Docker + Caddy HTTPS): `SERVER=ec2-user@<도메인> KEY=<pem> deploy/deploy.sh` (docs/DEPLOY.md). 로그인은 Google (docs/decisions/004).
`scripts/verify.sh`, `plans/`는 아직 없다. 그 전까지는 `./gradlew test` 통과를 완료 기준으로 쓴다.

## 8. 코딩 규칙
- Java 21, Spring Boot 3.5.x, Spring Data JPA, Flyway, PostgreSQL, springdoc(Swagger), AWS SDK for Java v2, JUnit 5, Mockito, AssertJ, ArchUnit를 쓴다.
- DTO는 `record`, 불변 값은 `final`/`record`로 둔다. 의존성은 생성자 주입만 쓴다(필드 `@Autowired` 금지).
- 식별자·커밋 메시지는 영어, 사용자에게 보이는 문구와 문서는 한국어로 쓴다.
- 마이그레이션은 Phase마다 새 파일(`V{n}__설명.sql`)로 만든다. 이미 적용한 마이그레이션은 고치지 않는다.
- API나 테이블을 바꾸면 같은 변경에서 `docs/API.md`와 `docs/DATA_MODEL.md`도 고친다.
- 결정을 바꾸거나 새로 내리면 `docs/decisions/NNN-제목.md`(배경 / 결정 / 결과, 40줄 이내)를 추가한다.

## 9. 멈추고 사람에게 물을 때
- 고객 Role에 권한을 추가해야 할 것 같을 때
- Cost Explorer·Compute Optimizer·ELB 등 §10 허용 목록 밖의 고객 계정 API를 쓰고 싶을 때
- AWS 콘솔 작업이나 실제 계정 테스트가 필요할 때 (`docs/AWS_SETUP_GUIDE.md` 절 번호를 알려 준다)
- 비용이 드는 AWS 리소스(RDS, ALB, Fargate, NAT 등)를 만들어야 할 때
- API 계약이나 테이블 구조를 계획과 다르게 바꿔야 할 때
- `scripts/verify.sh`의 검사를 고치거나 끄고 싶을 때

## 10. 고객 Role 정책 기준 (`scripts/customer-role-allowlist.txt`와 동일하게 유지)
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "ReadEc2UsageOnly",
    "Effect": "Allow",
    "Action": ["ec2:DescribeInstances", "cloudwatch:GetMetricStatistics"],
    "Resource": "*",
    "Condition": {"StringEquals": {"aws:RequestedRegion": ["ap-northeast-2"]}}
  }]
}
```
- 리전 목록은 연결마다 사용자가 고른 리전으로 CloudFormation 파라미터에서 채운다.
- 인스턴스 상세(AMI·EBS·보안그룹 등)가 필요해지면 `ec2:DescribeVolumes`·`DescribeImages`·`DescribeSecurityGroups`를
  **선택 권한**(CloudFormation 파라미터 기본값 꺼짐)으로 추가하는 ADR을 먼저 쓴다. ADR과 사람 승인 전에는 추가하지 않는다.
