-- 연결별 월 예산 (이슈 #27). 입력한 통화 그대로 저장하고 비교할 때만 환산한다.
-- 연결 삭제(DELETE /aws/accounts/{id})는 애플리케이션이 이 행을 먼저 지운다 (ON DELETE CASCADE를 두지 않는 기존 규칙).

CREATE TABLE account_budgets
(
    account_id UUID PRIMARY KEY REFERENCES aws_accounts (id),
    owner_id   VARCHAR(64)              NOT NULL,
    amount     NUMERIC(15, 2)           NOT NULL,
    currency   VARCHAR(3)               NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_account_budgets_owner ON account_budgets (owner_id);
