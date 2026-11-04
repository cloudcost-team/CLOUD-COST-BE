package com.cloudcost.domain.ec2;

import java.util.Set;

/**
 * EC2 상태 이름(pending, running, stopping, stopped, shutting-down, terminated) 판단.
 */
public final class InstanceStates {

    /**
     * 이 상태들에서는 켜져 있는 구간이 있을 수 있어 CloudWatch 데이터가 생길 수 있다.
     */
    private static final Set<String> MAY_EMIT_METRICS =
        Set.of("pending", "running", "stopping", "shutting-down");

    private InstanceStates() {
    }

    public static boolean isRunning(String state) {
        return "running".equals(state);
    }

    /** 중지됨·중지 중 (대시보드의 "중지 N대") */
    public static boolean isStopped(String state) {
        return "stopped".equals(state) || "stopping".equals(state);
    }

    public static boolean mayEmitMetrics(String state) {
        return state != null && MAY_EMIT_METRICS.contains(state);
    }
}
