-- CloudFormation 빠른 생성 + SNS 신호로 연결 (ADR-003)
-- 스택 삭제 신호를 받으면 DISCONNECTED로 바꾼다.

ALTER TABLE aws_accounts DROP CONSTRAINT ck_aws_accounts_status;
ALTER TABLE aws_accounts ADD CONSTRAINT ck_aws_accounts_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'ERROR', 'DISCONNECTED'));

ALTER TABLE aws_accounts ADD COLUMN stack_id VARCHAR(512);
ALTER TABLE aws_accounts ADD COLUMN disconnected_at TIMESTAMP WITH TIME ZONE;
