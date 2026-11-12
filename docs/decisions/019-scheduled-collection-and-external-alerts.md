# ADR-019: 예약 수집과 외부 알림 발송 (ADR-002·014 보완)

- 상태: 채택 (2026-11-12, 아키텍처 변경안 "변경 1")

## 배경
- 수집(ADR-002)과 알림 평가(ADR-014)가 사용자가 조회할 때만 돌아서, 접속하지 않으면 예산 초과 알림이 생기지 않았다.
  "청구서 전에 미리 알림"이라는 핵심 약속이 구조상 동작하지 않는다.
- 알림은 헤더 벨에만 있어 서비스를 열어야 보인다.

## 결정
- **예약 수집**: `CollectionScheduler`가 정해진 시각(기본 KST 03·09·15·21시, `CCI_COLLECTION_CRON`)에 수집할 연결
  (ACTIVE, 확인된 적 있는 ERROR)마다 작업 1건을 SQS 수집 큐에 넣는다. `CollectionWorker`가 꺼내 기존 `Ec2DataRefresher`로
  수집하고(`sync_jobs.trigger_type = SCHEDULED`), 이어서 `AlertEvaluationService`(기존 `NotificationService.evaluate` 분리)로 평가한다.
  캐시 규칙(인스턴스 10분·CloudWatch 1시간·1회 500건)은 그대로라 고객 계정 호출이 늘지 않는다.
- **SQS를 쓰는 이유**: 연결별 실패 격리, 재시도·DLQ(재전송 정책 maxReceiveCount 3), 콜백 큐와 같은 운영 방식.
  메시지에는 owner·연결 id만 넣고, 워커는 둘이 함께 맞는 행을 DB에서 다시 읽는다. AWS 오류는 연결 상태에 남기고 작업을 끝내며,
  DB 오류 등만 다시 시도한다. `CCI_COLLECTION_QUEUE_URL`이 비어 있으면 예약 수집 전체를 끈다.
- **알림 생성과 발송 분리(Outbox)**: `notifications` 행이 보낼 일이고, `notification_deliveries`가 채널별 발송 상태다.
  `NotificationDispatchService`가 1분마다 ① 켜진 채널 × (채널 등록 뒤·3일 안에 생긴) 알림 중 기록이 없는 쌍을 PENDING으로 만들고
  ② 차례가 된 것을 보낸다. 실패하면 1분·5분·30분·2시간 뒤 다시, 5번째거나 웹훅 삭제(401·403·404) 같은 실패면 FAILED로 멈춘다.
  발송이 실패해도 알림은 서비스 안 알림창에 남는다.
- **채널**: Discord 웹훅만 구현한다. `https://discord.com/api/webhooks/{id}/{token}` 형식만 받고(SSRF 방지), 보내기 직전에 다시 검사하며
  리다이렉트를 따라가지 않는다. `@everyone` 등 멘션은 막는다. 주소는 비밀 값이라 API에는 가린 주소만, 로그에는 토큰을 가리고, DB에는 AES-256-GCM으로 암호화해 저장한다(V13).
  Slack은 같은 어댑터에 형식만 더하면 되고, 이메일(SES)은 도메인 인증·샌드박스 해제가 필요해 발표 이후로 미룬다.
- **잠금**: 서버 1대라 기존 계정별 `ReentrantLock`과 단일 스레드 발송으로 충분하다. 워커를 다른 서버로 나누면
  `FOR UPDATE SKIP LOCKED`로 바꾼다. 스케줄 스레드는 4개로 늘려 콜백 폴링·수집·발송이 서로 막지 않게 한다.
- **하지 않을 것**: Kafka·WebSocket. 비용 데이터는 1시간 단위로 바뀌어 실시간 스트리밍이 필요 없다.

## 결과
- 접속하지 않아도 하루 4번 수집·평가되고, 등록한 Discord로 바로 알림이 간다. 조회 시점 수집·평가는 그대로 남는다.
- 운영에 SQS 큐·DLQ 2개와 백엔드 Role 권한(sqs:SendMessage·ReceiveMessage·DeleteMessage)이 더 필요하다 (AWS_SETUP_GUIDE).
- 주간 요약은 월요일 첫 예약 수집(03시)에 만들어진다. 보내는 시각을 바꾸려면 cron만 바꾸면 된다.
