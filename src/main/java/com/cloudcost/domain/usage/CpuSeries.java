package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 인스턴스 1대의 시간별 CPU 사용률 (S07-2 "탐지 근거 · 최근 14일 CPU 사용률" 그래프).
 * 꺼져 있던 시간은 데이터 포인트가 없어 점이 없다 (그래프에서 끊긴다).
 *
 * @param points 시간 순. CPU 지표가 있는 시간만
 * @param max    가장 높았던 값 (= {@link UtilizationStats#cpuMax()}). 점이 없으면 null
 * @param maxAt  가장 높았던 시간 (같은 값이면 앞선 시간). 점이 없으면 null
 */
public record CpuSeries(List<Point> points, BigDecimal max, Instant maxAt) {

    private static final int SCALE = 2;

    /**
     * @param hour 시간 시작 (UTC)
     * @param avg  그 시간 평균 (%)
     * @param max  그 시간 최대 (%). 최대 값이 없으면 평균
     */
    public record Point(Instant hour, BigDecimal avg, BigDecimal max) {
    }

    /** 같은 인스턴스의 샘플만 넘긴다 */
    public static CpuSeries of(List<HourlySample> samples) {
        List<Point> points = samples.stream()
            .filter(s -> s.cpuAvg() != null)
            .sorted(Comparator.comparing(HourlySample::hourStart))
            .map(s -> new Point(s.hourStart(), scale(s.cpuAvg()), scale(s.cpuMax() != null ? s.cpuMax() : s.cpuAvg())))
            .toList();
        Point top = null;
        for (Point p : points) {
            if (top == null || p.max().compareTo(top.max()) > 0) {
                top = p;
            }
        }
        return new CpuSeries(points, top == null ? null : top.max(), top == null ? null : top.hour());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
