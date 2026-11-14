-- 서버(컨테이너)가 여러 대여도 같은 일을 한 곳에서만 하게 하는 잠금 (ADR-021, JdbcClusterLock).
-- 행 하나가 잠금 하나다. locked_until이 지나면 누구든 다시 잡을 수 있다 (잡은 서버가 죽어도 저절로 풀린다).
CREATE TABLE cluster_locks (
    name         VARCHAR(100)             NOT NULL PRIMARY KEY,
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by    VARCHAR(36)              NOT NULL
);
