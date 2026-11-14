package com.cloudcost.adapter.persistence;

import com.cloudcost.application.port.ClusterLock;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * cluster_locks 테이블의 임대(lease) 행으로 잠근다 (ADR-021).
 * 잠금을 잡은 동안 DB 연결을 붙잡지 않는다 (AWS 호출이 길어도 연결 풀을 쓰지 않는다).
 * 트랜잭션 밖에서 불러야 한다 (각 SQL이 바로 커밋되어야 다른 서버가 본다).
 *
 * <p>일이 도는 동안 maxHold의 1/3마다 임대를 연장한다. 그래서 일이 오래 걸려도 다른 곳이 넘겨받지 않고,
 * 잡은 서버가 죽었을 때만(연장이 멈췄을 때만) maxHold 뒤에 풀린다.
 * 서버가 멈췄다가(긴 GC 등) maxHold를 넘긴 뒤 되살아나는 경우는 막지 못한다. 이때는 경고 로그를 남긴다.
 */
@Component
class JdbcClusterLock implements ClusterLock {

    static final Duration POLL = Duration.ofMillis(200);

    private static final Logger log = LoggerFactory.getLogger(JdbcClusterLock.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cluster-lock-renewer");
        t.setDaemon(true);
        return t;
    });

    JdbcClusterLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        renewer.shutdownNow();
    }

    @Override
    public <T> T runExclusively(String name, Duration maxHold, Supplier<T> work) {
        String token = UUID.randomUUID().toString();
        while (!tryAcquire(name, token, maxHold)) {
            pause();
        }
        ScheduledFuture<?> renewal = keepAlive(name, token, maxHold);
        try {
            return work.get();
        } finally {
            renewal.cancel(false);
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
        ScheduledFuture<?> renewal = keepAlive(name, token, maxHold);
        try {
            work.run();
            return true;
        } finally {
            renewal.cancel(false);
            Instant earliest = acquiredAt.plus(minHold);
            Instant now = clock.instant();
            release(name, token, earliest.isAfter(now) ? earliest : now);
        }
    }

    private ScheduledFuture<?> keepAlive(String name, String token, Duration maxHold) {
        long every = Math.max(1, maxHold.toMillis() / 3);
        return renewer.scheduleAtFixedRate(() -> renew(name, token, maxHold), every, every, TimeUnit.MILLISECONDS);
    }

    private void renew(String name, String token, Duration maxHold) {
        try {
            int updated = jdbc.update("UPDATE cluster_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
                Timestamp.from(clock.instant().plus(maxHold)), name, token);
            if (updated == 0) {
                log.warn("Cluster lock lost while work is running: {}", name);
            }
        } catch (RuntimeException e) {
            log.warn("Cluster lock renewal failed: {} ({})", name, e.getClass().getSimpleName());
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
