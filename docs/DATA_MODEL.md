# DATA MODEL — PostgreSQL 16 (Flyway)

> 마이그레이션: `src/main/resources/db/migration/V{n}__설명.sql`. 적용한 파일은 고치지 않고 새 파일을 만든다.
> 테이블을 바꾸면 이 문서를 같은 변경에서 고친다. 테스트는 H2(PostgreSQL 모드)로 같은 마이그레이션을 돌린다.

## 공통 규칙
- 사용자 데이터 테이블은 모두 `owner_id`를 갖고, 조회는 항상 `owner_id`로 제한한다 (자식 테이블에도 중복 저장).
- 시각은 `TIMESTAMP WITH TIME ZONE`(UTC). 금액·단가는 `NUMERIC`.
- 자격증명 컬럼(access key, secret, session token 등)은 어떤 이름으로도 만들지 않는다.

## 관계
```
users 1─* aws_accounts 1─* ec2_instances
                       1─* ec2_usage_hourly
                       1─* sync_jobs
                       1─1 account_budgets
                       1─* notifications
alert_settings (owner별 1행)
ec2_price_cache (공개 단가, owner 없음)
```

## V1 (2026-10-08)

### users
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | VARCHAR(64) PK | owner id (현재 X-Owner-Id, 이후 JWT sub) |
| created_at | TIMESTAMPTZ | |

### aws_accounts — 연결 1건
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | UUID PK | API의 accountId |
| owner_id | VARCHAR(64) FK | |
| region | VARCHAR(32) | 조회 리전 |
| external_id | VARCHAR(64) UNIQUE | `cci-` + hex 24자 |
| role_arn, aws_account_id | | Role 등록 후 |
| status | PENDING / ACTIVE / ERROR / DISCONNECTED | 전이 규칙은 ARCHITECTURE.md "연결 상태 전이" (ADR-006) |
| last_error_code, last_error_message | | 마지막 AWS 오류 분류(예: `PERMISSION_DENIED`)와 정제한 한국어 메시지. 수집이 모두 성공하면 지운다 |
| verified_at | | 실제 AssumeRole 검증 성공 시각 |
| instances_refreshed_at | | 마지막 DescribeInstances (10분 캐시 기준) |
| usage_refreshed_at | | 마지막 사용량 수집 시도 시각, 성공·실패 무관 (1시간 1회 기준). ERROR 연결은 이 시각 + 1시간에 다시 확인한다 |

### ec2_instances — 저장 허용 필드만
UNIQUE `(account_id, instance_id)` — 같은 인스턴스는 갱신만 한다.
| 컬럼 | 설명 |
|---|---|
| instance_id, name(Name 태그), instance_type, region, availability_zone, state, launch_time, platform_details, tenancy, spot, detailed_monitoring | DescribeInstances |
| present | 마지막 DescribeInstances에 있었는지. 삭제돼도 행은 남긴다 |
| active_since_usage_sync | 지난 CloudWatch 수집 이후 켜진 모습을 봤는지 (꺼져 있던 인스턴스는 다시 조회하지 않기 위해) |
| usage_collected_until | 이 시각 전까지 CloudWatch를 수집함. NULL이면 소급 전. 성공한 구간 끝까지만 옮기므로 실패한 구간은 다음 수집에서 다시 읽는다 |
| first_seen_at, last_seen_at | |

### ec2_usage_hourly — CloudWatch 원본 (시간 단위)
UNIQUE `(account_id, instance_id, hour_start)` — 다시 읽으면 갱신한다(upsert). 2시간 겹침·실패 구간 재수집에도 행이 늘지 않는다.
| 컬럼 | 설명 |
|---|---|
| hour_start | 데이터 포인트 시각 (UTC 정시) |
| sample_count | 그 시간의 SampleCount. 일별·월별 사용 시간과 비용은 조회 시 domain이 계산한다 |
| collected_at | |

### sync_jobs — AWS 수집 1회 기록
| 컬럼 | 설명 |
|---|---|
| trigger_type | QUERY (조회 시점 수집) / MANUAL (예약) |
| status | RUNNING / SUCCEEDED / PARTIAL(일부만 저장) / FAILED(아무것도 저장 못 함) |
| instances_refreshed, usage_refreshed | 이번에 갱신한 것 |
| ec2_request_count, **cw_request_count** | 호출 수 (CloudWatch 무료 한도 추적). 재시도도 센다 |
| instance_count, usage_rows | |
| error_code, error_message, warning | FAILED·PARTIAL의 첫 원인(한국어)과 나머지 안내. 자격증명·AWS 원문 금지 |

### ec2_price_cache — 공개 단가 (owner 없음)
UNIQUE `(region, instance_type, operating_system, tenancy, license_model, pre_installed_sw)`.
`usd_per_hour`가 NULL이면 "단가 없음" 결과 (1일 캐시).

## V2 (2026-10-08) — 스택 신호 (ADR-003)
- `aws_accounts.status`에 `DISCONNECTED` 추가 (고객이 스택을 지움. 기록은 남기고 수집하지 않는다)
- `aws_accounts.stack_id` VARCHAR(512): Role을 만든 고객 CloudFormation 스택 ARN
- `aws_accounts.disconnected_at`
- 신호 처리용 조회: `external_id`(UNIQUE)로 찾고 owner는 찾은 행에서 읽는다. 사용자 요청 경로에서는 쓰지 않는다.

## V3 (2026-10-13) — Google 로그인 (ADR-004)
`users`에 컬럼 추가: `email`, `display_name`, `auth_provider`(google | NULL=로컬 DevAuth), `provider_subject`(Google sub), `last_login_at`.
UNIQUE `(auth_provider, provider_subject)`. 비밀번호 컬럼은 없다. `users.id`(UUID 문자열)가 owner_id다.

## V4 (2026-10-13) — 환율 캐시 (ADR-005)
`exchange_rates`: base, quote, rate NUMERIC(18,6), rate_date, source, fetched_at. UNIQUE `(base, quote, rate_date)`. 공개 데이터라 owner 없음.
6시간 안에 받은 값이 있으면 외부 API를 부르지 않는다.

## 마이그레이션 없음 (2026-10-30) — 수집 정합성 (ADR-006)
기존 컬럼만 쓴다: PARTIAL의 실패 원인은 `sync_jobs.error_code`·`error_message`, 연결의 마지막 오류는 `aws_accounts.last_error_*`.

## V5 (2026-10-30) — 연결 이름 (이슈 #15)
- `aws_accounts.alias` VARCHAR(160) NULL: 사용자가 붙인 연결 이름. 40자(코드 포인트) 제한·공백 정리는 애플리케이션(`ConnectionAlias`)에서 한다. AWS에는 보내지 않는다.
- 연결 삭제(`DELETE /aws/accounts/{id}`)는 `ec2_usage_hourly` → `ec2_instances` → `sync_jobs` → `aws_accounts` 순서로 `owner_id`·`account_id` 조건을 걸어 한 트랜잭션에서 지운다
  (FK에 ON DELETE CASCADE를 두지 않고 애플리케이션이 지운다). 연결 갱신(`save`)은 이미 지운 연결을 다시 만들지 않는다.

## V6 (2026-11-04) — 낭비 탐지용 지표 (이슈 #24, ADR-009)
- `ec2_usage_hourly`에 컬럼 추가 (모두 NULL 허용, 지표 수집 전 행은 NULL):
  | 컬럼 | 설명 |
  |---|---|
  | cpu_avg, cpu_max | NUMERIC(7,3). 같은 CPUUtilization 조회의 Average·Maximum (%) |
  | network_in_bytes, network_out_bytes | BIGINT. NetworkIn·NetworkOut Sum. **최근 14일만** 수집 |
- `ec2_instances.metrics_collected_until` TIMESTAMPTZ: 이 시각 전까지 최근 14일 범위의 지표를 수집함. NULL이면 다음 수집 때 최근 14일을 읽는다
  (지표 수집 전에 저장한 인스턴스는 CPU도 한 번 다시 읽는다). 성공한 구간 끝까지만 옮긴다.
- 네트워크 값은 이미 있는 시간 행에만 채운다 (꺼져 있던 시간은 행이 없다).

## V7 (2026-11-05) — 연결별 월 예산 (이슈 #27)
### account_budgets
| 컬럼 | 타입 | 설명 |
|---|---|---|
| account_id | UUID PK FK aws_accounts | 연결 하나에 예산 하나 |
| owner_id | VARCHAR(64) | 조회·삭제는 항상 owner 조건 |
| amount | NUMERIC(15,2) | 입력한 금액 (원화는 원 단위, 달러는 센트 단위) |
| currency | VARCHAR(3) | KRW / USD. 입력한 통화 그대로 저장하고 비교할 때만 환율로 바꾼다 |
| updated_at | TIMESTAMPTZ | |
- 연결 삭제 순서: `ec2_usage_hourly` → `ec2_instances` → `sync_jobs` → **`account_budgets`** → `aws_accounts` (한 트랜잭션).

## V8 (2026-11-05) — 서비스 안 알림 (이슈 #35, ADR-014)
### alert_settings — 사용자별 알림 조건 (행이 없으면 모두 켜짐)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| owner_id | VARCHAR(64) PK | |
| budget80, budget100, budget120, spike, weekly_summary | BOOLEAN NOT NULL DEFAULT TRUE | 알림 종류별 on/off |
| updated_at | TIMESTAMPTZ | |

### notifications — 알림 1건
UNIQUE `(owner_id, event_key)` — 같은 일은 한 번만 만든다. 인덱스 `(owner_id, created_at DESC, id DESC)`(목록 커서), `(owner_id, read_at)`(안 읽은 수).
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | UUID PK | |
| owner_id | VARCHAR(64) | 조회·읽음 처리는 항상 owner 조건 |
| account_id | UUID FK aws_accounts | 알림이 생긴 연결 |
| type | VARCHAR(20) | BUDGET / SPIKE / WEEKLY_SUMMARY |
| message | VARCHAR(500) | 만들 때 정한 한국어 문구 (금액은 그때의 통화·환율) |
| event_key | VARCHAR(200) | `BUDGET:{연결}:{yyyy-MM}:{80|100|120}` · `SPIKE:{연결}:{날짜}` · `WEEKLY_SUMMARY:{연결}:{주 월요일}` |
| created_at | TIMESTAMPTZ | |
| read_at | TIMESTAMPTZ NULL | 읽지 않았으면 NULL |
- 연결 삭제 순서: `ec2_usage_hourly` → `ec2_instances` → `sync_jobs` → `account_budgets` → **`notifications`** → `aws_accounts` (한 트랜잭션).

## 보존 (백로그)
ec2_usage_hourly는 455일, sync_jobs는 90일, notifications는 읽은 지 90일 정도로 정리하는 작업이 필요하다.
