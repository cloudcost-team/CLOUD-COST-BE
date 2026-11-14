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
 * 연장 SQL에는 짧은 제한 시간을 두고, 연장 스레드를 여러 개 둬 한 호출이 멈춰도 다른 잠금의 연장이 밀리지 않게 한다.
 * 서버가 maxHold보다 길게 멈췄다가 되살아나는 경우(긴 GC 등)는 막지 못한다. 이때는 경고 로그를 남긴다 (ADR-021).
 */
@Component
class JdbcClusterLock implements ClusterLock {

    static final Duration POLL = Duration.ofMillis(200);
    static final int RENEW_TIMEOUT_SECONDS = 10;
    static final int RENEW_THREADS = 4;

    private static final Logger log = LoggerFactory.getLogger(JdbcClusterLock.class);

    private final JdbcTemplate jdbc;
    private final JdbcTemplate renewJdbc;
    private final Clock clock;
    private final ScheduledExecutorService renewer = Executors.newScheduledThreadPool(RENEW_THREADS, r -> {
        Thread t = new Thread(r, "cluster-lock-renewer");
        t.setDaemon(true);
        return t;
    });

    JdbcClusterLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        // 연장 전용: 제한 시간을 이 SQL에만 건다 (다른 쿼리에는 영향 없음)
        this.renewJdbc = new JdbcTemplate(jdbc.getDataSource());
        this.renewJdbc.setQueryTimeout(RENEW_TIMEOUT_SECONDS);
    }

    @PreDestroy
    void shutdown() {
        renewer.shutdownNow();
    }

    @Override
    public <T> T runExclusively(String name, Duration maxHold, Supplier<T> work) {
        Lease lease = new Lease(name, maxHold);
        while (!tryAcquire(lease)) {
            pause();
        }
        lease.startRenewing();
        try {
            return work.get();
        } finally {
            lease.close(() -> jdbc.update("DELETE FROM cluster_locks WHERE name = ? AND locked_by = ?", name, lease.token));
        }
    }

    @Override
    public boolean runIfFree(String name, Duration minHold, Duration maxHold, Runnable work) {
        Lease lease = new Lease(name, maxHold);
        Instant acquiredAt = clock.instant();
        if (!tryAcquire(lease)) {
            return false;
        }
        lease.startRenewing();
        try {
            work.run();
            return true;
        } finally {
            lease.close(() -> {
                Instant earliest = acquiredAt.plus(minHold);
                Instant now = clock.instant();
                jdbc.update("UPDATE cluster_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
                    Timestamp.from(earliest.isAfter(now) ? earliest : now), name, lease.token);
            });
        }
    }

    private boolean tryAcquire(Lease lease) {
        Instant now = clock.instant();
        Timestamp until = Timestamp.from(now.plus(lease.maxHold));
        try {
            jdbc.update("INSERT INTO cluster_locks (name, locked_until, locked_by) VALUES (?, ?, ?)",
                lease.name, until, lease.token);
            return true;
        } catch (DuplicateKeyException e) {
            return jdbc.update(
                "UPDATE cluster_locks SET locked_until = ?, locked_by = ? WHERE name = ? AND locked_until <= ?",
                until, lease.token, lease.name, Timestamp.from(now)) == 1;
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("잠금을 기다리다 중단되었습니다", e);
        }
    }

    /** 잠금 한 번. 연장과 해제가 같은 모니터를 써서, 해제한 뒤에는 연장이 만료 시각을 다시 쓰지 못한다. */
    private final class Lease {

        final String name;
        final Duration maxHold;
        final String token = UUID.randomUUID().toString();
        private ScheduledFuture<?> renewal;
        private boolean closed;

        Lease(String name, Duration maxHold) {
            this.name = name;
            this.maxHold = maxHold;
        }

        void startRenewing() {
            long every = Math.max(1, maxHold.toMillis() / 3);
            renewal = renewer.scheduleAtFixedRate(this::renew, every, every, TimeUnit.MILLISECONDS);
        }

        synchronized void renew() {
            if (closed) {
                return;
            }
            try {
                int updated = renewJdbc.update(
                    "UPDATE cluster_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
                    Timestamp.from(clock.instant().plus(maxHold)), name, token);
                if (updated == 0) {
                    log.warn("Cluster lock lost while work is running: {}", name);
                }
            } catch (RuntimeException e) {
                log.warn("Cluster lock renewal failed: {} ({})", name, e.getClass().getSimpleName());
            }
        }

        synchronized void close(Runnable release) {
            closed = true;
            if (renewal != null) {
                renewal.cancel(false);
            }
            release.run();
        }
    }
}
