package com.cloudcost.domain.demo;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;

/**
 * 체험 모드 서버의 사용 패턴. 시각은 zone(기본 Asia/Seoul) 기준이다.
 */
public enum DemoSchedule {
    ALWAYS("24시간 켜 둠"),
    WEEKDAY_OFFICE("평일 09~19시"),
    DAILY_HOURS("매일 09시부터 N시간"),
    MONTHLY_SPIKE("매일 09시부터 N시간 · 매달 12일은 24시간 (급증 체험)");

    public static final int DAY_START_HOUR = 9;
    /** MONTHLY_SPIKE가 하루 종일 켜지는 날 */
    public static final int SPIKE_DAY_OF_MONTH = 12;

    private final String label;

    DemoSchedule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * @param hoursPerDay DAILY_HOURS·MONTHLY_SPIKE에서만 쓴다 (1~24)
     */
    /** 하루 사용 시간(hoursPerDay)을 받는 패턴인지 */
    public boolean usesHoursPerDay() {
        return this == DAILY_HOURS || this == MONTHLY_SPIKE;
    }

    public boolean isOn(ZonedDateTime hour, int hoursPerDay) {
        return switch (this) {
            case ALWAYS -> true;
            case WEEKDAY_OFFICE -> hour.getDayOfWeek() != DayOfWeek.SATURDAY && hour.getDayOfWeek() != DayOfWeek.SUNDAY
                && hour.getHour() >= DAY_START_HOUR && hour.getHour() < 19;
            case DAILY_HOURS -> Math.floorMod(hour.getHour() - DAY_START_HOUR, 24) < hoursPerDay;
            case MONTHLY_SPIKE -> hour.getDayOfMonth() == SPIKE_DAY_OF_MONTH
                || Math.floorMod(hour.getHour() - DAY_START_HOUR, 24) < hoursPerDay;
        };
    }
}
