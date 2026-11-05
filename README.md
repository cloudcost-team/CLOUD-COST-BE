# CloudCost Backend

> AWS 계정을 **읽기 전용 IAM Role**로 연결하기만 하면,
> 내 EC2가 얼마나 켜져 있었고 비용이 얼마쯤 나왔는지 보여 주고,
> 낭비되는 서버·비용 급증·예산 초과를 찾아 **얼마를 아낄 수 있는지**까지 알려 줍니다.
> Access Key는 받지 않고, 고객 리소스는 절대 바꾸지 않습니다.
> AWS 계정이 없어도 **체험 모드**로 같은 화면을 볼 수 있습니다.

<br />

## Architecture
<img alt="CloudCost 아키텍처" src="assets/architecture.png" />



<br />

## API Docs

| 구분 | URL |
|------|-----|
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI | `http://localhost:8080/v3/api-docs` |
| 로그인 테스트 | `http://localhost:8080/login.html` |
| API 계약 | [docs/API.md](docs/API.md) (모든 경로는 `/api/v1` 아래) |

<br />

## 주요 기능

모든 계산(사용 시간·비용·급증·절감액·비교)은 백엔드가 하고, 프론트는 받은 값을 그대로 그립니다.

<table>
  <tr>
    <td align="center" width="33%">
      <b>원클릭 AWS 연결</b>
      <br />
      <sub>CloudFormation 빠른 생성 링크 → 체크 1개 → 연결 완료<br />SNS·SQS 신호로 자동 검증, 연결 이름 변경·삭제</sub>
    </td>
    <td align="center" width="33%">
      <b>EC2 사용 시간 · 추정 비용</b>
      <br />
      <sub>CloudWatch 기록으로 켜져 있던 시간 계산 (90일 소급)<br />사용 시간 × 온디맨드 단가, 일별·월별 · KRW/USD</sub>
    </td>
    <td align="center" width="33%">
      <b>대시보드 · 월 예산</b>
      <br />
      <sub>이번 달 비용·월말 예상·전월 같은 기간 대비<br />연결별 월 예산, 지난 3개월 평균·슬라이더 범위</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <b>낭비 리소스 탐지</b>
      <br />
      <sub>최근 14일 CPU·네트워크로 과다 사양·유휴 판정<br />더 싼 t3 추천, 월 절감액·근거·콘솔 변경 방법</sub>
    </td>
    <td align="center" width="33%">
      <b>비용 급증 알림</b>
      <br />
      <sub>평소(직전 30일 중앙값) 대비 급증일 판정<br />사용 시간이 가장 많이 늘어난 원인 서버 지목</sub>
    </td>
    <td align="center" width="33%">
      <b>비용 시뮬레이터</b>
      <br />
      <sub>추천 적용 · 트래픽 증가율을 넣으면<br />월·연 절감액과 예산 안인지 계산 (저장 안 함)</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <b>팀 벤치마킹</b>
      <br />
      <sub>비슷한 서버 대수 구간 팀과 과다 사양 비율·월 지출 비교<br />Azure Public Dataset V2에서 계산한 실제 분포</sub>
    </td>
    <td align="center" width="33%">
      <b>서비스 안 알림</b>
      <br />
      <sub>예산 80·100·120%, 비용 급증, 주간 요약<br />헤더 벨·알림 목록·읽음 처리 (외부 채널 없음)</sub>
    </td>
    <td align="center" width="33%">
      <b>월간 리포트</b>
      <br />
      <sub>달마다 비용·전월 대비·급증일·서버별 비중<br />그 달의 절감 제안 건수·금액 (최대 14개월)</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <b>체험 모드</b>
      <br />
      <sub>AWS 계정 없이 서버 구성·사용 모양을 고르면<br />실제 연결과 같은 계산으로 모든 화면 체험</sub>
    </td>
    <td align="center" width="33%">
      <b>Google 로그인 · 고객 비용 보호</b>
      <br />
      <sub>Google ID 토큰 검증 → 자체 JWT (비밀번호 저장 안 함)<br />조회 시점 수집 + DB 캐시 (CloudWatch 계정당 1시간 1회)</sub>
    </td>
    <td align="center" width="33%">
      <b>읽기 전용 보안</b>
      <br />
      <sub>고객 계정 허용 API 2개만 호출 (ArchUnit 강제)<br />사용자별 데이터 격리, 남의 리소스는 404</sub>
    </td>
  </tr>
</table>

<br />

## 기술 스택

### Backend
- **Java 21**
- **Spring Boot 3.5** - Spring Web MVC, Validation
- **Spring Data JPA** - ORM
- **Springdoc OpenAPI** - Swagger UI

### Database
- **PostgreSQL 16**
- **Flyway** - DB 마이그레이션

### AWS 연동 (AWS SDK for Java v2)
- **STS** - 고객 Role AssumeRole (+External ID)
- **EC2 · CloudWatch** - 인스턴스 정보, 사용 시간 (고객 계정, 읽기 전용)
- **Price List** - 온디맨드 단가 (우리 계정)
- **SNS · SQS · CloudFormation** - 원클릭 연결 신호

### 외부 데이터
- **Frankfurter** - USD→KRW 환율 (키 없음, DB 캐시 6시간)
- **Azure Public Dataset V2** - 팀 벤치마킹 비교 기준 (`scripts/benchmark/`로 재계산 → `benchmark/reference.json`)

### 인증
- **Google Identity Services** - 로그인
- **Nimbus JOSE + JWT** - Google ID 토큰 검증, 자체 JWT 발급

### 테스트
- **JUnit 5 · Mockito · AssertJ**
- **ArchUnit** - 헥사고날 의존 규칙, 허용 AWS API 검사
- **H2** - 테스트용 DB (실제 AWS 호출 없음)
- **`@Tag("aws")`** - 실제 AWS를 부르는 테스트는 `./gradlew awsTest`로만 실행

### 인프라 · 배포
- **EC2 + Docker Compose** - Caddy(HTTPS 자동) → 앱 → PostgreSQL
- **GitHub Actions** - CI(테스트), CD(자동 배포, AWS는 OIDC)
- **Grafana Cloud + Alloy** - 서버(VM)·PostgreSQL·앱(Actuator/Micrometer) 지표 모니터링 (서버 밖에서 확인)

### 협업
- **CodeRabbit** - PR 자동 코드 리뷰 (`.coderabbit.yaml`)
- **Discord 웹훅** - PR·이슈 알림 봇
- **Claude Code 하네스** - `.harness` 서브모듈(BE·FE 공통 기획 문서·규칙·스킬), `.claude → .harness/be` 심링크

<br />

## 프로젝트 구조

헥사고날 아키텍처를 따르며, 의존 방향은 ArchUnit 테스트로 강제합니다.

```text
src/main/java/com/cloudcost/
  CloudCostApplication.java
  domain/                 # 순수 Java 계산 (Spring·AWS·JPA 금지)
    account/              # AWS 연결, External ID, 고객 Role 정책, 빠른 생성 링크
    ec2/                  # 인스턴스 정보, 수집 상태
    usage/                # 사용 시간 계산, 일별·월별 집계, CPU·네트워크 사용률 통계
    cost/                 # 추정 비용, Price List 조회 조건, 통화
    budget/               # 월 예산, 월말 예상, 지난 3개월 평균·슬라이더 범위
    waste/                # 과다 사양·유휴 판정, 인스턴스 사양 카탈로그, 절감액
    spike/                # 비용 급증일 판정, 원인 서버
    simulation/           # 추천 적용·트래픽 증가 시뮬레이션
    benchmark/            # 팀 벤치마킹 (서버 대수 구간, 백분위 위치)
    alert/                # 알림 조건(예산·급증·주간 요약), 알림 문구
    report/               # 월간 리포트 (EMPTY → FIRST_MONTH → PREVIEW → FINAL)
    demo/                 # 체험 모드 서버 구성·스케줄·사용 모양
    user/                 # 사용자, 외부 로그인 신원
    sync/                 # 수집 기록
  application/            # 유스케이스 + 포트(interface) (AWS SDK·JPA 직접 사용 금지)
    port/
    error/
  adapter/                # 포트 구현 (AWS SDK는 여기서만)
    awscustomer/          # 고객 계정: STS·EC2·CloudWatch
    awsinternal/          # 우리 계정: Price List·SQS·CloudFormation 응답
    auth/                 # Google ID 토큰 검증, JWT
    fx/                   # Frankfurter 환율
    benchmark/            # 벤치마킹 비교 기준 JSON 읽기
    persistence/          # JPA 엔티티, Repository (owner 조건 필수)
  entrypoint/
    api/                  # REST 컨트롤러, DTO, 인증(owner 해석), 예외 처리
    worker/               # 스택 신호 SQS 폴러
    logging/              # 로그 민감 값 마스킹
    config/               # 조립(wiring), 설정, Swagger

src/main/resources/
  application.yml         # 공통 설정 (값은 환경 변수)
  application-local.yml   # 로컬 프로필
  application-prod.yml    # 운영 프로필
  db/migration/           # Flyway 마이그레이션 (V1 ~ V8)
  cloudformation/         # 고객용 Read Only Role 템플릿
  benchmark/reference.json # 팀 벤치마킹 비교 기준 (교체 가능한 데이터)
  static/login.html       # 로그인 테스트 페이지

scripts/
  customer-role-allowlist.txt   # 고객 Role 허용 IAM 액션 원본
  benchmark/AzureOversized.java # Azure 원본 → 비교 기준 재계산 (단일 파일 실행)
deploy/                   # Dockerfile, docker-compose, Caddyfile, Alloy·Grafana, 배포 스크립트
docs/                     # 아키텍처·API·데이터 모델·보안·배포 문서, ADR
.github/                  # GitHub Actions(CI·CD·Discord 알림), 이슈·PR 템플릿
.harness/                 # Claude Code 하네스 서브모듈 (.claude가 .harness/be를 가리킴)
```

<br />

## 시작하기

### 요구사항

- Java 21
- Docker (로컬 PostgreSQL)
- AWS CLI 프로필 (실제 AWS 연결 시, [docs/AWS_SETUP_GUIDE.md](docs/AWS_SETUP_GUIDE.md))

### 클론

하네스가 서브모듈이므로 함께 받습니다.

```bash
git clone --recurse-submodules https://github.com/cloudcost-team/CLOUD-COST-BE.git
```

이미 클론했다면 `git submodule update --init`을 실행합니다.

### 로컬 실행

```bash
docker compose up -d db
./gradlew bootRun
```

기본 `AUTH_MODE=dev`로 뜹니다. 실제 AWS 연결에 필요한 환경 변수(`CCI_BACKEND_ROLE_ARN`, `CCI_CALLBACK_QUEUE_URL` 등)는 [docs/AWS_SETUP_GUIDE.md](docs/AWS_SETUP_GUIDE.md) 3절을 따릅니다.
AWS 없이 둘러보려면 체험 모드(`POST /api/v1/demo/login`, 기본 `cloudcost` / `1234`)를 사용합니다.

### 테스트

실제 AWS를 호출하지 않습니다 (H2 + fake AWS 어댑터).

```bash
./gradlew test
```

특정 테스트만 실행할 때는 다음 형식을 사용합니다.

```bash
./gradlew test --tests com.cloudcost.application.UsageRefreshTest
```

실제 AWS 계정을 부르는 테스트는 따로 실행합니다 (`CCI_IT_ROLE_ARN` 등 환경 변수 필요).

```bash
./gradlew awsTest
```

<br />

## CI/CD

GitHub Actions로 테스트와 배포를 자동화합니다.

| 워크플로우 | 트리거 | 내용 |
|-----------|--------|------|
| `ci.yaml` | `dev`, `main` 대상 PR · `main` push | JDK 21 → `./gradlew test` → 실패 시 테스트 리포트 업로드 |
| `cd.yaml` | `dev` push (머지, `*.md`만 바뀌면 제외) | CI 통과 → jar 빌드 → OIDC로 AWS 역할 → 러너 IP만 SSH 임시 허용 → 서버로 복사 → `docker compose up -d --build` → 기동 확인 → SSH 허용 해제 |

| `discord-notify.yaml` | PR 열림·머지, 이슈 등록 | Discord로 리뷰 요청·머지·새 이슈 알림 (`CLOUDCOST_DISCORD_PR_ISSUE_WEBHOOK_URL` Secret) |

설정 방법: [docs/DEPLOY.md](docs/DEPLOY.md) 10절

<br />

## 개발 규칙

- 계산(사용 시간, 비용, 캐시 판단)은 `domain/`에 두고 프론트에서 계산하지 않습니다.
- 고객 AWS 계정에는 `ec2:DescribeInstances`, `cloudwatch:GetMetricStatistics`만 호출합니다 ([허용 목록](scripts/customer-role-allowlist.txt)). 권한 추가는 사람 승인 + ADR이 먼저입니다.
- 모든 사용자 데이터 조회는 `owner_id` 조건을 겁니다. `findById`·`findAll` 같은 owner 없는 조회를 만들지 않습니다.
- DB 스키마 변경은 `src/main/resources/db/migration/`에 새 Flyway 파일로 추가합니다. 적용한 파일은 고치지 않습니다.
- API·테이블을 바꾸면 `docs/API.md`, `docs/DATA_MODEL.md`도 함께 고칩니다. 판단 기준을 정하거나 바꾸면 ADR을 남깁니다.
- 기능 범위·문구는 하네스의 기획 문서(기능명세서·화면 설계)를 따릅니다.
- 민감한 값(키, 비밀번호, 실제 계정 ID·ARN)은 커밋하지 않고 환경 변수 또는 배포 Secret으로 관리합니다.

<br />

## 브랜치 전략

```text
main                     # 안정 버전
dev                      # 개발 통합 · 머지되면 자동 배포
feat/#이슈번호-기능명
fix/#이슈번호-버그명
```

예시:

```text
feat/#12-cost-forecast
fix/#34-usage-timezone
```

<br />

## 커밋 컨벤션

커밋 메시지는 `타입: 한국어 설명` 형식입니다.

| 타입 | 설명 |
|------|------|
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 코드 리팩토링 |
| `docs` | 문서 수정 |
| `test` | 테스트 코드 |
| `chore` | 빌드, 설정, 배포 변경 |

예시:

```text
feat: 일별 추정 비용 API 추가
fix: KST 날짜 경계 사용 시간 집계 오류 수정
```

<br />

## PR 규칙

- 이슈를 먼저 만들고 이슈 템플릿(feature·refactor·fix·chore)을 사용합니다.
- `dev`로 PR을 올리고 [PR 템플릿](.github/PULL_REQUEST_TEMPLATE.md)의 체크리스트를 채웁니다.
- CI(`./gradlew test`)가 통과해야 머지합니다. 머지하면 자동 배포됩니다.

<br />

## 문서

| 문서 | 내용 |
|------|------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 헥사고날 구조, 수집·캐시 흐름, 환경 변수 |
| [docs/API.md](docs/API.md) | API 계약 |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | 테이블 |
| [docs/SECURITY.md](docs/SECURITY.md) | 보안 규칙 |
| [docs/AWS_SETUP_GUIDE.md](docs/AWS_SETUP_GUIDE.md) | AWS 계정 준비 |
| [docs/DEPLOY.md](docs/DEPLOY.md) | EC2 배포, CI/CD |
| [docs/decisions/](docs/decisions/) | ADR 001~016 (연결 방식, 수집·캐시, 통화, 체험 모드, 낭비 탐지, 급증, 시뮬레이터, 벤치마킹, 알림, 월간 리포트 등) |
