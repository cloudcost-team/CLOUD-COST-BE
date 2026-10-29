package com.cloudcost.application;

import java.time.Duration;

/** 재시도 사이에 기다린다 (테스트에서는 기다리지 않고 기록만 한다). */
public interface Pauser {

    void pause(Duration duration);
}
