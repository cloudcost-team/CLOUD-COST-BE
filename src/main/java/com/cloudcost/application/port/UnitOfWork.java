package com.cloudcost.application.port;

import java.util.function.Supplier;

/**
 * 여러 저장을 한 트랜잭션으로 묶는다. AWS 호출은 트랜잭션 밖에서 한다(DB 연결을 오래 잡지 않기 위해).
 */
public interface UnitOfWork {

    <T> T inTransaction(Supplier<T> work);

    default void run(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
