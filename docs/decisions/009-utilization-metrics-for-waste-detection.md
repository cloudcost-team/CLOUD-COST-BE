# ADR-009: 낭비 탐지용 CPU·네트워크 지표 수집

- 상태: 제안 (2026-11-04, 이슈 #24)

## 배경
- 낭비 리소스(과다 사양·유휴)와 팀 벤치마킹(CPU P95 10% 미만 서버 비율)은 "켜져 있는 동안 얼마나 썼는지"가 필요하다.
  지금까지는 사용 시간 계산용 `CPUUtilization` `SampleCount`만 받았다.
- 고객 Role 권한은 `ec2:DescribeInstances`·`cloudwatch:GetMetricStatistics` 두 개뿐이고 늘리지 않는다 (CLAUDE.md §0).
- `GetMetricStatistics`는 `Statistics`와 `ExtendedStatistics`(p95)를 한 요청에 섞을 수 없어, p95를 받으려면 호출이 2배가 된다.
  `GetMetricData`는 유료라 쓰지 않는다. 메모리 사용률은 EC2 기본 지표에 없다 (CloudWatch Agent 필요).

## 결정
- 기존 `CPUUtilization` 요청에 `Average`·`Maximum`을 더한다 (`SampleCount`·`Average`·`Maximum`). **호출 수는 그대로다.**
- P95는 AWS에서 받지 않고 **시간별 평균값들의 95번째 백분위(nearest-rank)** 로 계산한다 (`UtilizationStats`).
  1분 단위 P95보다 낮게 나올 수 있으므로 화면·응답에 "시간 평균 기준"임을 밝힌다.
- 유휴 판단용 `NetworkIn`·`NetworkOut`(Sum)은 **최근 14일만** 수집한다. 수집할 때마다 인스턴스당 2건이 늘어난다.
  `ec2_instances.metrics_collected_until`로 진행을 따로 기록하고, 사용 시간과 같은 2시간 겹침·실패 구간 재수집 규칙을 쓴다.
- 지표 수집 전에 저장한 인스턴스(`metrics_collected_until` NULL)는 최근 14일 CPU를 **한 번** 다시 읽어 평균·최대를 채운다.
  같은 수집에서 사용 시간 조회가 그 구간을 이미 덮었으면 다시 읽지 않는다.
- 사용 시간을 끝까지 수집한 인스턴스만 지표를 이어서 수집한다. 지표 수집이 실패해도 사용 시간 진행은 저장한다.
- 체험 모드는 서버마다 사용 모양(`NORMAL`·`OVERSIZED`·`IDLE`)을 고르고, 인스턴스 ID·시각으로 정한 결정적 값으로 같은 샘플을 만든다.

## 결과
- 고객 계정 CloudWatch 호출: 첫 연결은 인스턴스당 3건 → 5건, 이후 1시간 1회 수집은 1건 → 3건.
  인스턴스 20대가 24시간 조회돼도 월 약 4.3만 건으로 무료 한도(월 100만 건) 안이다.
- 판단 근거는 CPU와 네트워크뿐이다. 메모리를 많이 쓰는 서버를 과다 사양으로 잘못 볼 수 있어, 추천에 "메모리 먼저 확인" 안내를 붙인다 (#10).
- 14일보다 앞의 네트워크 값은 없다(NULL). 14일 기준 판단에만 쓴다.
