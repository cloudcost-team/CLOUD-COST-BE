-- 원화 환산용 환율 캐시 (ADR-005). 공개 데이터라 owner_id가 없다.
CREATE TABLE exchange_rates (
    id          UUID                     PRIMARY KEY,
    base        VARCHAR(3)               NOT NULL,
    quote       VARCHAR(3)               NOT NULL,
    rate        NUMERIC(18, 6)           NOT NULL,
    rate_date   DATE                     NOT NULL,
    source      VARCHAR(100)             NOT NULL,
    fetched_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_exchange_rates UNIQUE (base, quote, rate_date)
);
CREATE INDEX idx_exchange_rates_pair_fetched ON exchange_rates (base, quote, fetched_at);
