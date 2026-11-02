package com.cloudcost.fakes;

import com.cloudcost.application.Pauser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 기다리지 않고 기다리려던 시간만 기록한다 (재시도 백오프 검증용). */
public class RecordingPauser implements Pauser {

    public final List<Duration> pauses = new ArrayList<>();
    /** 기다리는 순간마다 실행한다 (재시도 중간 상태 검증용). null이면 아무것도 하지 않는다. */
    public Runnable onPause;

    @Override
    public void pause(Duration duration) {
        pauses.add(duration);
        if (onPause != null) {
            onPause.run();
        }
    }
}
