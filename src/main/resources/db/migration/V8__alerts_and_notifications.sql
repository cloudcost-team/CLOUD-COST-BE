-- 서비스 안 알림 (이슈 #35, ADR-014). 알림 조건은 사용자가 조회할 때 평가하고, 같은 알림은 event_key로 한 번만 만든다.
-- 연결 삭제(DELETE /aws/accounts/{id})는 애플리케이션이 그 연결의 notifications를 먼저 지운다 (ON DELETE CASCADE를 두지 않는 기존 규칙).

CREATE TABLE alert_settings
(
    owner_id       VARCHAR(64) PRIMARY KEY,
    budget80       BOOLEAN                  NOT NULL DEFAULT TRUE,
    budget100      BOOLEAN                  NOT NULL DEFAULT TRUE,
    budget120      BOOLEAN                  NOT NULL DEFAULT TRUE,
    spike          BOOLEAN                  NOT NULL DEFAULT TRUE,
    weekly_summary BOOLEAN                  NOT NULL DEFAULT TRUE,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE notifications
(
    id         UUID PRIMARY KEY,
    owner_id   VARCHAR(64)              NOT NULL,
    account_id UUID                     NOT NULL REFERENCES aws_accounts (id),
    type       VARCHAR(20)              NOT NULL,
    message    VARCHAR(500)             NOT NULL,
    event_key  VARCHAR(200)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_notifications_owner_event UNIQUE (owner_id, event_key)
);
CREATE INDEX idx_notifications_owner_created ON notifications (owner_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_owner_unread ON notifications (owner_id, read_at);
