# ARCHITECTURE

## 흐름
```
Swagger / 프론트
   │  Authorization: Bearer <JWT> (Google 로그인, ADR-004) · 로컬 dev는 X-Owner-Id 허용
   ▼
entrypoint/api (Spring MVC)
   ▼
application
   ├─ AwsAccountService      연결 생성(External ID, 빠른 생성 링크) · Role 검증(실제 AssumeRole)
   ├─ StackCallbackService   고객 스택 신호 처리 → 검증(재시도) → CloudFormation 응답 / 삭제 시 DISCONNECTED
   ├─ Ec2UsageQueryService   인스턴스 · 기간 합계 · 일별 · 월별
   │     └─ Ec2DataRefresher  캐시 확인 → 필요할 때만 AWS 호출 → DB 저장 (계정 단위 잠금)
   ├─ PriceService           온디맨드 단가 (DB 캐시 7일)
   └─ CurrencyService        원화 환산 환율 (Frankfurter, DB 캐시 6시간, ADR-005)
   ▼ ports
adapter/awscustomer  ── STS AssumeRole → EC2 DescribeInstances, CloudWatch GetMetricStatistics  [고객 계정]
adapter/awsinternal  ── Price List GetProducts (us-east-1), SQS 신호 수신, CloudFormation 응답 PUT  [우리 계정]
entrypoint/worker    ── StackCallbackPoller: SQS long polling (CCI_CALLBACK_QUEUE_URL 있을 때만)
adapter/persistence  ── JPA + Flyway (PostgreSQL / 테스트는 H2)
```

## 연결 (ADR-003)
```
POST /aws/accounts → External ID + quickCreateUrl
고객: 링크 → [스택 생성] → Read Only Role + Custom Resource → 우리 SNS → SQS
StackCallbackPoller → StackCallbackService: 연결 ID·External ID 확인 → AssumeRole + DescribeInstances (10초 간격 최대 6회)
  → ACTIVE + SUCCESS 응답 (실패: FAILED → 고객 스택 롤백)
스택 삭제 → Delete 신호 → DISCONNECTED + SUCCESS
```

## 조회 시점 수집 (ADR-002)
| 데이터 | AWS 재호출 간격 | 그 안의 재조회 |
|---|---|---|
| 인스턴스 정보 (DescribeInstances) | 10분 | DB |
| 사용량 (CloudWatch) | **계정당 1시간 1회** | DB |
| 단가 (Price List) | 7일 (없음 결과는 1일) | DB |

- 첫 CloudWatch 수집: 인스턴스마다 최근 90일을 30일씩 3회. 이후: 마지막 수집 2시간 전부터 1회.
- 지난 수집 이후 켜진 적이 없는 인스턴스는 CloudWatch를 부르지 않는다.
- AWS 호출이 실패해도 이전 데이터가 있으면 그 데이터와 `freshness.warnings`를 준다. 처음이면 오류로 응답한다.

### 정합성 · 오류 처리 (ADR-006)
- 시간별 사용량은 `(account_id, instance_id, hour_start)` upsert라 2시간 겹침이나 재수집으로 중복되지 않는다.
- 인스턴스별 `usage_collected_until`은 성공한 구간 끝까지만 옮긴다. 일부 구간·인스턴스가 실패해도 성공분은 저장되고,
  실패한 구간은 다음 수집(1시간 뒤)에서 다시 읽는다. 결과는 `sync_jobs.status=PARTIAL` + `error`(한국어 원인).
- 재시도(`AwsRetry`): 요청 제한·일시 장애만 1초·2초 백오프로 최대 3회. SDK 자체 재시도는 꺼서 호출 수를 정확히 센다.

| 오류 분류 | 예 | 연결 상태 | 이번 수집 |
|---|---|---|---|
| `ASSUME_ROLE_DENIED` | Role 삭제, External ID·Trust Policy 불일치 | ERROR | 중단 |
| `PERMISSION_DENIED` | AccessDenied, UnauthorizedOperation | ERROR | 중단 |
| `REGION_DISABLED` | RegionDisabledException, OptInRequired | ERROR | 중단 |
| `THROTTLED` | Throttling, RequestLimitExceeded | 유지 (`lastError`만) | 재시도 후 중단 |
| `UNAVAILABLE`, `UNKNOWN` | 5xx, 네트워크 | 유지 (`lastError`만) | 재시도 후 그 인스턴스만 건너뜀 |
| `BACKEND_CREDENTIALS_MISSING` | 서버 자격증명 없음 | 유지 | 중단 |

### 연결 상태 전이 (`AwsAccount`가 강제)
```
PENDING ──검증 성공──▶ ACTIVE ──고쳐야 하는 오류──▶ ERROR ──1시간 뒤 재확인 성공──▶ ACTIVE
   └──검증 실패──▶ ERROR          └──스택 삭제 신호──▶ DISCONNECTED ◀──스택 삭제 신호(검증된 적 있는 ERROR)
DISCONNECTED: 수집하지 않음. 새 스택으로 실제 검증에 성공할 때만 ACTIVE
```
- 검증된 뒤 ERROR가 된 연결은 조회할 수 있다(저장된 데이터 + 원인 경고). AWS는 1시간에 한 번만 다시 부른다.
- 허용되지 않은 전이(PENDING에서 수집 성공 처리, DISCONNECTED에서 수집 등)는 `IllegalStatusTransitionException`.

## 계산 (domain, 순수 Java)
| 클래스 | 하는 일 |
|---|---|
| `UsageCalculator` | 시간별 SampleCount → 사용 분. CPUUtilization은 켜져 있는 동안 1분마다 샘플을 남기므로 SampleCount = 사용 분 (최대 60) |
| `UsageAggregator` | 시간별 → 일별(Asia/Seoul 날짜 경계) → 월별(일별 합산) |
| `CostEstimator` | 사용 시간 × 온디맨드 단가 = 추정 비용. 단가 없으면 null |
| `CollectionWindows` | CloudWatch 조회 구간: 30일 단위, 최대 455일 |
| `CurrentRun` | 실행 중이면 LaunchTime ~ 지금 (현재 실행 구간) |
| `RefreshPolicy` | 캐시 만료 판단 |

## 의존 규칙 (ArchUnit으로 강제)
| 계층 | 허용 | 금지 |
|---|---|---|
| domain | JDK | Spring, AWS SDK, JPA, Jackson |
| application | domain, ports | AWS SDK, JPA, Spring Web/Data, Jackson, adapter, entrypoint |
| adapter/awscustomer ↔ adapter/awsinternal | — | 서로 import |
| AWS SDK | adapter 안에서만 | 그 밖 |

조립(wiring)은 `entrypoint/config/ApplicationWiring`에서 한다. application 클래스에는 Spring 어노테이션이 없다.

## 환경변수
| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_ENV` | local | |
| `AUTH_MODE` | dev | dev는 local에서만 허용 |
| `DEV_OWNER_ID` | local-dev-user | 헤더가 없을 때의 사용자 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | localhost:5432/cci, cci, (local 프로필만 cci) | |
| `GOOGLE_CLIENT_ID` | (없음) | Google 로그인 웹 클라이언트 ID (공개 값) |
| `JWT_SECRET` | (없음, dev는 임시 키) | 액세스 토큰 서명 키 32바이트 이상. google 모드 필수 |
| `CCI_BACKEND_ROLE_ARN` | (없음) | 고객 Trust Policy Principal |
| `CCI_TEMPLATE_URL` | (없음) | S3에 올린 고객용 템플릿 |
| `CCI_CALLBACK_TOPIC_ARN` | (없음) | 스택 신호 SNS 토픽 (스택 리전 = 토픽 리전) |
| `CCI_CALLBACK_QUEUE_URL` | (없음) | 비어 있으면 신호 수신기를 켜지 않는다 |
| `AWS_PROFILE` | | 백엔드 자격증명 프로필 (로컬) |
| `CCI_INSTANCE_TTL` / `CCI_USAGE_TTL` / `CCI_USAGE_BACKFILL` | 10m / 1h / 90d | |
| `CCI_MAX_CW_REQUESTS` | 500 | 수집 1회 CloudWatch 호출 상한 |
| `cci.fx.url` / `cci.fx.refresh-interval` | Frankfurter USD→KRW / 6h | 환율 API·재조회 간격 |
| `MANAGEMENT_PORT` | 8081 | Actuator(`/actuator/health`, `/actuator/prometheus`) 전용 포트. 외부(Caddy)로 열지 않는다 |
| `COMPOSE_PROFILES` | (없음) | 서버에서 `monitoring`이면 Alloy 에이전트 실행 |
| `GRAFANA_CLOUD_PROM_URL` / `GRAFANA_CLOUD_PROM_USER` / `GRAFANA_CLOUD_API_KEY` | (없음) | Alloy가 지표를 보낼 Grafana Cloud 주소·인스턴스 ID·토큰. 서버 `.env`에만 |

## 모니터링
서버 안의 Grafana Alloy 컨테이너가 1분마다 지표를 모아 Grafana Cloud로 **보내기만** 한다 (들어오는 포트 없음).
| 대상 | 수집 방법 | job |
|---|---|---|
| VM (CPU·메모리·디스크·네트워크) | Alloy 내장 node exporter, 호스트 `/proc`·`/sys`·`/` 읽기 전용 | `integrations/node_exporter` |
| PostgreSQL (연결·크기·트랜잭션) | Alloy 내장 postgres exporter → `127.0.0.1:5432` | `integrations/postgres_exporter` |
| 앱 (HTTP 응답 시간·오류·JVM·커넥션 풀) | `127.0.0.1:8081/actuator/prometheus` | `cloudcost-app` |
서버가 죽어도 대시보드·알림은 Grafana Cloud에 남아 있어 장애를 밖에서 알 수 있다. 설정 파일: `deploy/alloy/config.alloy`.

## 아직 없는 것 (기획서 대비)
- 사용량 예약 수집(조회가 있을 때만 수집), 이메일+비밀번호 로그인, 리프레시 토큰, demo 데이터 API, 프론트 연동.
