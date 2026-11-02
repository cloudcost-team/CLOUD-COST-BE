package com.cloudcost.domain.demo;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;

/**
 * 체험 모드 서버의 사용 패턴. 시각은 zone(기본 Asia/Seoul) 기준이다.
 */
public enum DemoSchedule {
    ALWAYS("24시간 켜 둠"),
    WEEKDAY_OFFICE("평일 09~19시"),
    DAILY_HOURS("매일 09시부터 N시간");

    public static final int DAY_START_HOUR = 9;

    private final String label;

    DemoSchedule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param hoursPerDay DAILY_HOURS에서만 쓴다 (1~24)
     */
    public boolean isOn(ZonedDateTime hour, int hoursPerDay) {
        return switch (this) {
            case ALWAYS -> true;
            case WEEKDAY_OFFICE -> hour.getDayOfWeek() != DayOfWeek.SATURDAY && hour.getDayOfWeek() != DayOfWeek.SUNDAY
                && hour.getHour() >= DAY_START_HOUR && hour.getHour() < 19;
            case DAILY_HOURS -> Math.floorMod(hour.getHour() - DAY_START_HOUR, 24) < hoursPerDay;
        };
    }
}
