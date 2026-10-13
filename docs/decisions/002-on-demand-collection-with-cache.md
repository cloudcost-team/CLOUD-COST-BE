# ADR-002: 조회 시점에 수집하고 DB를 캐시로 쓴다 (CloudWatch 1시간 1회)

- 상태: 승인됨 (2026-10-08, 사용자 확인)

## 배경
- 사용 시간 출처로 CloudWatch, CloudTrail, DescribeInstances 상태 누적을 비교했다.
  CloudWatch `GetMetricStatistics`는 연결 전 최대 455일을 소급할 수 있고 OS 내부 shutdown도 놓치지 않는다.
  대신 요청이 **고객 계정** 요청으로 집계된다 (월 100만 건 무료, 초과 시 1,000건당 $0.01).
- 사용자 요구: "콘솔에서 조회하면 백엔드가 가져와 보여 주고 DB에 저장, 최근에 가져온 것이 있으면 DB 것을 쓴다."

## 결정
- 출처는 CloudWatch `GetMetricStatistics`(AWS/EC2 CPUUtilization, Period 3600, Statistic SampleCount)로 한다.
  시간당 SampleCount를 그 인스턴스의 "꽉 찬 한 시간" 샘플 수와 비교해 분 단위로 근사한다.
- 조회 API가 호출되면 캐시 기간을 확인한다.
  - 인스턴스 정보(DescribeInstances, 무료): 10분
  - CloudWatch: **계정당 1시간에 1회**. 그 안의 재조회는 DB에 저장된 시간별 값으로 응답한다.
    실패해도 호출 시각을 기록해 1시간 안에 다시 부르지 않는다.
- 처음에는 90일을 소급(30일 × 3회/인스턴스), 이후에는 마지막 수집 2시간 전부터만 읽는다.
  지난 수집 이후 켜진 적이 없는 인스턴스는 CloudWatch를 부르지 않는다. 수집 1회 상한 500건.
- 같은 계정을 동시에 조회해도 AWS는 한 번만 부르도록 계정 단위로 잠근다.
- 모든 수집은 `sync_jobs`에 남기고 `cw_request_count`로 CloudWatch 호출 수를 기록한다.

## 결과
- 인스턴스 20대 기준 월 호출 수는 최악(계속 새로고침)에도 약 1.4만 건으로 무료 한도의 1.4% 수준이다.
  "0원 보장"이 아니라 "사실상 0원"이다. 고객이 다른 도구로 무료 한도를 이미 다 썼다면 소액이 청구될 수 있다.
- 기획서의 Scheduler → Sync Job 구조는 아직 없다. 조회가 없으면 수집도 없다. 이후 스케줄러를 붙여도
  같은 `Ec2DataRefresher`를 호출하면 된다.
- GET 요청이 DB에 쓰기를 한다(캐시 채우기). 응답 `freshness`로 새로 가져왔는지 알려 준다.
