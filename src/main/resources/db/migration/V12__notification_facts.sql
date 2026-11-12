-- 외부 채널(Discord embed) 메시지 필드에 쓸 숫자를 알림을 만들 때 같이 저장한다 (ADR-019).
-- 보낼 때 다시 조회하면 그새 바뀐 예산·월말 예상이 들어가므로 만든 시점 값을 남긴다. 금액은 USD, 이전 알림은 모두 NULL.
ALTER TABLE notifications ADD COLUMN threshold_percent INTEGER;
ALTER TABLE notifications ADD COLUMN budget_usd NUMERIC(20, 6);
ALTER TABLE notifications ADD COLUMN forecast_usd NUMERIC(20, 6);
ALTER TABLE notifications ADD COLUMN spike_date DATE;
ALTER TABLE notifications ADD COLUMN spike_ratio NUMERIC(20, 1);
ALTER TABLE notifications ADD COLUMN spike_cause VARCHAR(255);
