# ADR-006: 수집 정합성 · AWS 오류 처리 · 연결 상태 전이

- 상태: 제안 (2026-10-30, 이슈 #14)

## 배경
- 실제 고객 계정 연결을 늘리기 전에, 수집이 일부 실패해도 데이터가 중복·손상되지 않고 빠진 구간이 저절로 채워져야 한다.
- 지금까지는 CloudWatch 구간 하나가 실패하면 그 수집 전체를 멈췄고, 권한 오류가 나도 연결은 ACTIVE로 남아 원인이 보이지 않았다.
  ERROR가 되면 조회가 409로 막혀 고객이 권한을 고쳐도 복구되지 않았다.

## 결정
- 시간별 사용량은 `(account_id, instance_id, hour_start)`로 upsert한다. 한 번에 들어온 같은 시각은 하나로 합친다.
- 인스턴스별 `usage_collected_until`은 **성공한 구간의 끝까지만** 옮긴다. 실패한 구간을 건너뛰지 않으므로 다음 수집이 그 구간부터 다시 읽는다.
  성공한 구간은 바로 저장한다(이후 실패와 무관). 새 테이블·컬럼은 만들지 않았다.
- 오류 분류 (`AwsAccessException`):
  - 고쳐야 하는 오류(Role 거부·권한 없음·리전 비활성) → 연결 **ERROR** + `lastError`(한국어). 이번 수집을 멈춘다.
  - 일시 오류(요청 제한·AWS 장애) → 1초·2초 백오프로 최대 3회 시도. 그래도 실패하면 상태는 그대로, `lastError`만 남긴다.
    요청 제한은 이번 수집을 멈추고, 그 밖의 일시 오류는 해당 인스턴스만 건너뛴다.
  - 서버 자격증명 문제는 고객 잘못이 아니므로 연결 상태를 바꾸지 않는다.
- SDK 자체 재시도는 끈다. 재시도는 application 한 곳에서 하고, 시도마다 `cw_request_count`에 센다(상한 500건도 재시도 포함).
- 상태 전이는 `AwsAccount`가 강제한다. PENDING→ACTIVE/ERROR, ACTIVE→ERROR/DISCONNECTED, ERROR→ACTIVE(복구)/DISCONNECTED,
  DISCONNECTED→(실제 재검증으로만) ACTIVE. 그 밖은 `IllegalStatusTransitionException`.
- 검증된 뒤 ERROR가 된 연결은 계속 조회할 수 있다(저장된 데이터 + 오류 원인). 1시간에 한 번 전체를 다시 확인해 성공하면 ACTIVE로 돌아온다.
- Role 삭제와 External ID 불일치는 AWS가 같은 AccessDenied로 답해 구분할 수 없다. 그래서 AssumeRole 거부는 ERROR로 두고,
  DISCONNECTED는 스택 삭제 신호(확실한 근거)로만 바꾼다.
- `sync_jobs`는 기존 컬럼을 쓴다: 일부 실패는 `PARTIAL` + `error_code`·`error_message`(첫 원인), 나머지는 `warning`.

## 결과
- 같은 구간을 몇 번 다시 읽어도 결과가 같다. 실패한 구간은 다음 수집(1시간 뒤)에 채워진다.
- ERROR 연결의 복구는 최대 1시간 늦을 수 있다. 바로 확인하려면 Role을 다시 등록(PUT .../role)한다.
- 요청 처리 중 재시도로 응답이 몇 초 늦어질 수 있다(최대 1+2초 대기).
