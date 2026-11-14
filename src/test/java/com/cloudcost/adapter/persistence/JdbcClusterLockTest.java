package com.cloudcost.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudcost.IntegrationTestBase;
import com.cloudcost.application.port.ClusterLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 서버가 여러 대여도 같은 일을 한 곳에서만 하는지 (ADR-021) */
class JdbcClusterLockTest extends IntegrationTestBase {

    @Autowired ClusterLock lock;

    @Test
    void 같은_이름은_동시에_한_곳에서만_실행하고_끝나면_다음이_이어받는다() throws Exception {
        String name = "test:" + UUID.randomUUID();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int n = i;
                results.add(pool.submit(() -> lock.runExclusively(name, Duration.ofMinutes(5), () -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    sleep(150);
                    running.decrementAndGet();
                    return n;
                })));
            }
            for (Future<Integer> r : results) {
                r.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxRunning.get()).as("동시에 두 곳에서 실행되지 않는다").isEqualTo(1);
    }

    @Test
    void 예약_작업은_한_회차에_한_번만_실행하고_최소_보유_시간이_지나면_다시_실행한다() {
        String name = "test:" + UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThat(lock.runIfFree(name, Duration.ofMinutes(10), Duration.ofMinutes(30), runs::incrementAndGet)).isTrue();
        assertThat(lock.runIfFree(name, Duration.ofMinutes(10), Duration.ofMinutes(30), runs::incrementAndGet))
            .as("같은 회차에 다른 서버가 깨어나도 건너뛴다").isFalse();

        clock.advance(Duration.ofMinutes(11));
        assertThat(lock.runIfFree(name, Duration.ofMinutes(10), Duration.ofMinutes(30), runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void 잡은_서버가_죽어도_최대_보유_시간이_지나면_풀린다() {
        String name = "test:" + UUID.randomUUID();
        // 잡은 채로 끝나지 않은 서버를 흉내: 실행 도중 다른 쪽이 잡으려 하면 실패한다
        AtomicInteger inner = new AtomicInteger();
        lock.runIfFree(name, Duration.ZERO, Duration.ofMinutes(5), () -> {
            assertThat(lock.runIfFree(name, Duration.ZERO, Duration.ofMinutes(5), inner::incrementAndGet)).isFalse();
            clock.advance(Duration.ofMinutes(6));
            assertThat(lock.runIfFree(name, Duration.ZERO, Duration.ofMinutes(5), inner::incrementAndGet))
                .as("최대 보유 시간이 지나면 다른 곳이 잡을 수 있다").isTrue();
        });
        assertThat(inner.get()).isEqualTo(1);
    }

    @Test
    void 일이_도는_동안은_임대를_연장해_최대_보유_시간이_지나도_넘겨주지_않는다() {
        String name = "test:" + UUID.randomUUID();
        Duration maxHold = Duration.ofMillis(600);
        AtomicInteger taken = new AtomicInteger();

        lock.runExclusively(name, maxHold, () -> {
            clock.advance(Duration.ofMillis(700)); // 연장이 없었다면 여기서 만료된다
            sleep(450);                             // 그동안 연장(200ms마다)이 돌아 만료 시각을 지금 + 600ms로 민다
            return lock.runIfFree(name, Duration.ZERO, maxHold, taken::incrementAndGet);
        });

        assertThat(taken.get()).as("일이 끝나기 전에는 다른 곳이 잡지 못한다").isZero();
        assertThat(lock.runIfFree(name, Duration.ZERO, maxHold, taken::incrementAndGet))
            .as("끝나면 바로 풀린다").isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
