package com.cloudcost.application.port;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 서버(컨테이너)가 여러 대일 때도 같은 일을 한 곳에서만 하게 하는 잠금 (ADR-021).
 * 프로세스 안 잠금(ReentrantLock)은 다른 서버에서 보이지 않아서 DB에 잠금을 둔다.
 */
public interface ClusterLock {

    /**
     * name으로 잠그고 work를 실행한다. 다른 곳에서 잡고 있으면 풀릴 때까지 기다린다.
     *
     * @param maxHold 잡은 서버가 죽어도 이 시간이 지나면 잠금이 저절로 풀린다
     */
    <T> T runExclusively(String name, Duration maxHold, Supplier<T> work);

    /**
     * 잡을 수 있으면 work를 실행하고 true, 다른 곳에서 잡고 있으면 건너뛰고 false.
     * 예약 작업용: 실행이 끝나도 minHold 동안은 다른 서버가 같은 회차를 다시 실행하지 못한다.
     */
    boolean runIfFree(String name, Duration minHold, Duration maxHold, Runnable work);
}
