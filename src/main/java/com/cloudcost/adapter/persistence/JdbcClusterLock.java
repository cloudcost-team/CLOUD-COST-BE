package com.cloudcost.adapter.persistence;

import com.cloudcost.application.port.ClusterLock;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * cluster_locks 테이블의 임대(lease) 행으로 잠근다 (ADR-021).
 * 잠금을 잡은 동안 DB 연결을 붙잡지 않는다 (AWS 호출이 길어도 연결 풀을 쓰지 않는다).
 * 트랜잭션 밖에서 불러야 한다 (각 SQL이 바로 커밋되어야 다른 서버가 본다).
 */
@Component
class JdbcClusterLock implements ClusterLock {

    static final Duration POLL = Duration.ofMillis(200);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcClusterLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public <T> T runExclusively(String name, Duration maxHold, Supplier<T> work) {
        String token = UUID.randomUUID().toString();
        while (!tryAcquire(name, token, maxHold)) {
            pause();
        }
        try {
            return work.get();
        } finally {
            jdbc.update("DELETE FROM cluster_locks WHERE name = ? AND locked_by = ?", name, token);
        }
    }

    @Override
    public boolean runIfFree(String name, Duration minHold, Duration maxHold, Runnable work) {
        String token = UUID.randomUUID().toString();
        Instant acquiredAt = clock.instant();
        if (!tryAcquire(name, token, maxHold)) {
            return false;
        }
        try {
            work.run();
            return true;
        } finally {
            Instant earliest = acquiredAt.plus(minHold);
            Instant now = clock.instant();
            release(name, token, earliest.isAfter(now) ? earliest : now);
        }
    }

    private boolean tryAcquire(String name, String token, Duration maxHold) {
        Instant now = clock.instant();
        Timestamp until = Timestamp.from(now.plus(maxHold));
        try {
            jdbc.update("INSERT INTO cluster_locks (name, locked_until, locked_by) VALUES (?, ?, ?)", name, until, token);
            return true;
        } catch (DuplicateKeyException e) {
            return jdbc.update(
                "UPDATE cluster_locks SET locked_until = ?, locked_by = ? WHERE name = ? AND locked_until <= ?",
                until, token, name, Timestamp.from(now)) == 1;
        }
    }

    private void release(String name, String token, Instant until) {
        jdbc.update("UPDATE cluster_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
            Timestamp.from(until), name, token);
    }

    private static void pause() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("잠금을 기다리다 중단되었습니다", e);
        }
    }
}
