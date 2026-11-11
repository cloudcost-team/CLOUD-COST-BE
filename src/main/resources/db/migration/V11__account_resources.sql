-- 방치 리소스·잊힌 리전 (ADR-020). Describe로 읽은 리소스와 연결별 확장 수집 상태.
-- 공인 IP 주소·태그 전체·보안그룹은 저장하지 않는다 (고정 IP는 allocation id로만 가리킨다).
-- 연결 삭제 때는 애플리케이션이 이 두 표를 먼저 지운다 (ON DELETE CASCADE를 두지 않는 기존 규칙).

CREATE TABLE account_resource_scans
(
    account_id         UUID PRIMARY KEY REFERENCES aws_accounts (id),
    owner_id           VARCHAR(64)              NOT NULL,
    status             VARCHAR(32)              NOT NULL,
    permission_version INT                      NOT NULL DEFAULT 1,
    scanned_at         TIMESTAMP WITH TIME ZONE,
    message            VARCHAR(500),
    CONSTRAINT ck_account_resource_scans_status
        CHECK (status IN ('NOT_SCANNED', 'OK', 'PERMISSION_UPGRADE_REQUIRED', 'FAILED'))
);
CREATE INDEX idx_account_resource_scans_owner ON account_resource_scans (owner_id, account_id);

CREATE TABLE account_resources
(
    id            UUID PRIMARY KEY,
    owner_id      VARCHAR(64)              NOT NULL,
    account_id    UUID                     NOT NULL REFERENCES aws_accounts (id),
    type          VARCHAR(20)              NOT NULL,
    resource_id   VARCHAR(255)             NOT NULL,
    name          VARCHAR(255),
    region        VARCHAR(32)              NOT NULL,
    subtype       VARCHAR(64),
    size_gb       INT,
    in_use        BOOLEAN                  NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE,
    request_count BIGINT,
    platform      VARCHAR(100),
    observed_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_account_resources_type CHECK (type IN ('INSTANCE', 'VOLUME', 'ADDRESS', 'SNAPSHOT', 'LOAD_BALANCER'))
);
CREATE INDEX idx_account_resources_owner_account ON account_resources (owner_id, account_id);
