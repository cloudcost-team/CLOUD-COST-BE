-- 예약 수집과 외부 알림 발송 (ADR-019).
-- 1) 예약 수집으로 만든 sync_job을 구분한다 (SCHEDULED).
-- 2) 사용자별 외부 알림 채널 (지금은 Discord 웹훅만).
-- 3) 알림 1건 × 채널 1개의 발송 기록. notifications 행이 곧 보낼 일(Outbox)이고, 이 표는 보낸 결과·재시도 상태다.
-- 연결·채널 삭제 때는 애플리케이션이 이 표를 먼저 지운다 (ON DELETE CASCADE를 두지 않는 기존 규칙).

ALTER TABLE sync_jobs DROP CONSTRAINT ck_sync_jobs_trigger;
ALTER TABLE sync_jobs ADD CONSTRAINT ck_sync_jobs_trigger CHECK (trigger_type IN ('QUERY', 'MANUAL', 'SCHEDULED'));

CREATE TABLE notification_channels
(
    id          UUID PRIMARY KEY,
    owner_id    VARCHAR(64)              NOT NULL,
    type        VARCHAR(20)              NOT NULL,
    webhook_url VARCHAR(300)             NOT NULL,
    enabled     BOOLEAN                  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_notification_channels_type CHECK (type IN ('DISCORD'))
);
CREATE INDEX idx_notification_channels_owner ON notification_channels (owner_id, created_at);

CREATE TABLE notification_deliveries
(
    id              UUID PRIMARY KEY,
    owner_id        VARCHAR(64)              NOT NULL,
    notification_id UUID                     NOT NULL REFERENCES notifications (id),
    channel_id      UUID                     NOT NULL REFERENCES notification_channels (id),
    status          VARCHAR(16)              NOT NULL,
    attempts        INT                      NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_deliveries UNIQUE (notification_id, channel_id),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX idx_notification_deliveries_due ON notification_deliveries (status, next_attempt_at);
CREATE INDEX idx_notification_deliveries_owner ON notification_deliveries (owner_id, notification_id);
