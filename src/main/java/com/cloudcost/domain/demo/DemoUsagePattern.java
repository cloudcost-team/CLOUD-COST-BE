package com.cloudcost.domain.demo;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;

/**
 * 체험 모드 서버가 켜져 있는 동안의 사용률 모양 (CPU·네트워크). 켜지는 시간은 {@link DemoSchedule}이 정한다.
 *
 * <p>값은 무작위가 아니라 인스턴스 ID와 시각으로 정한 결정적 흔들림(0~1)을 섞어 만든다.
 * 같은 설정이면 항상 같은 결과가 나와 시연·테스트에서 재현된다.
 */
public enum DemoUsagePattern {
    NORMAL("정상 · 업무 시간 CPU 40~70%"),
    OVERSIZED("과다 사양 · CPU 3~8%"),
    IDLE("유휴 · CPU 1% 미만, 네트워크 거의 없음");

    private static final long MB = 1024L * 1024L;
    private static final long KB = 1024L;

    private final String label;

    DemoUsagePattern(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 한 시간의 지표. cpu는 %, 네트워크는 바이트 */
    public record HourMetrics(double cpuAvg, double cpuMax, long networkInBytes, long networkOutBytes) {
    }

    /**
     * @param hour   그 시간의 시작 (zone 기준)
     * @param jitter 0 이상 1 미만의 결정적 흔들림
     */
    public HourMetrics at(ZonedDateTime hour, double jitter) {
        return switch (this) {
            case NORMAL -> {
                boolean busy = isOfficeHour(hour);
                double avg = busy ? 40 + 30 * jitter : 10 + 10 * jitter;
                long network = busy ? (50 + (long) (250 * jitter)) * MB : (10 + (long) (30 * jitter)) * MB;
                yield metrics(avg, Math.min(100, avg * 1.4 + 5), network);
            }
            case OVERSIZED -> {
                double avg = 3 + 5 * jitter;
                yield metrics(avg, avg * 2.5 + 3, (5 + (long) (20 * jitter)) * MB);
            }
            case IDLE -> {
                double avg = 0.2 + 0.6 * jitter;
                yield metrics(avg, avg + 1, (1 + (long) (19 * jitter)) * KB);
            }
        };
    }

    private static HourMetrics metrics(double avg, double max, long networkBytes) {
        long in = networkBytes * 2 / 5;
        return new HourMetrics(avg, max, in, networkBytes - in);
    }

    private static boolean isOfficeHour(ZonedDateTime hour) {
        DayOfWeek day = hour.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
            && hour.getHour() >= DemoSchedule.DAY_START_HOUR && hour.getHour() < 19;
    }
}
