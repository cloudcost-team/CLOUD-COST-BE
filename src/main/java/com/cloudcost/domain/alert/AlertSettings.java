package com.cloudcost.domain.alert;

/**
 * 사용자별 알림 조건 (S14). 정하지 않았으면 모두 켜져 있다. 알림은 서비스 안 알림창(헤더 벨·알림 목록)으로만 보낸다.
 */
public record AlertSettings(boolean budget80, boolean budget100, boolean budget120, boolean spike, boolean weeklySummary) {

    public static AlertSettings defaults() {
        return new AlertSettings(true, true, true, true, true);
    }

    /**
     * 넘은 가장 높은 예산 기준 이하에서 켜 둔 가장 높은 기준. 없으면 null.
     * 예: 120%를 넘었는데 120% 알림만 꺼 두었으면 100%로 알린다.
     *
     * @param reached 넘은 가장 높은 기준 (80·100·120). 없으면 null
     */
    public Integer budgetThresholdToNotify(Integer reached) {
        if (reached == null) {
            return null;
        }
        if (reached >= 120 && budget120) {
            return 120;
        }
        if (reached >= 100 && budget100) {
            return 100;
        }
        if (reached >= 80 && budget80) {
            return 80;
        }
        return null;
    }
}
