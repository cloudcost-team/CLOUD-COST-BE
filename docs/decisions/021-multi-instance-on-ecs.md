# ADR-021: 서버 여러 대(ECS) 대비 — DB 잠금과 백엔드 Role 재사용

- 상태: 제안 (2026-11-15, ECS 마이그레이션 준비. 인프라 전환은 별도 승인 후)

## 배경
- ECS로 옮기면 같은 Spring Boot 컨테이너가 2개 이상 뜬다 (무중단 배포, 한쪽 장애 대비).
- 지금 코드는 서버 1대를 전제로 했다.
  - `CollectionScheduler`(정해진 시각 수집), `NotificationDispatcher`(Discord 발송)가 컨테이너마다 돌아 **두 번씩** 실행된다.
  - `Ec2DataRefresher`, `ResourceScanService`의 연결별 잠금이 `ReentrantLock`이라 다른 컨테이너에서 보이지 않는다.
    같은 연결을 두 곳에서 동시에 수집해 고객 계정의 CloudWatch 호출이 늘어난다.
- 고객 Role의 Trust Policy는 우리 백엔드 Role ARN 하나만 믿는다 (SECURITY.md, 절대 규칙 5).

## 결정
- **DB 임대 잠금(`cluster_locks`, V14)**을 포트 `ClusterLock`과 어댑터 `JdbcClusterLock`으로 둔다.
  - 행 하나가 잠금 하나다. `locked_until`이 지나면 다시 잡을 수 있어, 잡은 컨테이너가 죽어도 저절로 풀린다.
  - 잠금을 잡은 동안 DB 연결을 붙잡지 않는다. AWS 호출을 트랜잭션 밖에서 하는 원칙(UnitOfWork)과 맞춘다.
  - ShedLock 같은 라이브러리 대신 직접 만든다. 테이블 1개와 SQL 3개면 충분하고, 기다리는 잠금(연결별 수집)도 같은 방식으로 처리한다.
- **연결별 수집**은 `runExclusively`로 잠근다. 다른 곳이 잡고 있으면 기다린 뒤 다시 읽는다 (기존 의미 그대로).
  최대 보유: EC2 수집 30분, 리소스 수집 15분.
- **예약 작업**은 `runIfFree`로 잠근다. 다른 곳이 잡고 있으면 건너뛴다.
  - 정해진 시각 수집: 한 회차를 10분 동안 다시 넣지 않는다 (컨테이너마다 시각이 몇 초 어긋나도 한 번만).
  - Discord 발송: 30초 동안 다시 돌지 않는다 (1분 주기).
- SQS 폴링(`CollectionWorker`, `StackCallbackPoller`)은 잠그지 않는다. SQS가 메시지를 한 곳에만 준다.
- **ECS Task Role은 지금 EC2가 쓰는 백엔드 Role을 그대로 쓴다.** Role의 Trust Policy에
  `ecs-tasks.amazonaws.com`만 추가한다. ARN이 바뀌지 않으므로 고객이 다시 연결할 필요가 없다.
  새 Role을 만들면 기존 고객 연결이 모두 AssumeRole에 실패한다.

## 결과
- 컨테이너가 몇 개든 예약 수집과 Discord 알림은 한 번만 실행되고, 같은 연결은 한 곳에서만 수집한다.
- 서버 1대(지금 EC2)에서도 그대로 동작한다. 잠금 SQL이 수집·발송마다 몇 번 더 나간다.
- 정리: `cluster_locks`의 연결별 행은 수집이 끝나면 지워진다. 예약 작업 행 2개만 남는다.
