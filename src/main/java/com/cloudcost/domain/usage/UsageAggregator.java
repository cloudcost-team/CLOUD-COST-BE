package com.cloudcost.domain.usage;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시간별 사용 분 → 일별 → 월별 집계. 기간 안의 날짜·월은 사용이 0이어도 모두 포함한다(차트용).
 *
 * <p>날짜 경계는 zone 기준이다(기본 Asia/Seoul). 시간 버킷은 시작 시각이 속한 날짜에 넣는다.
 * KST처럼 UTC와 정시 단위로 차이 나는 zone에서는 버킷이 날짜 경계를 넘지 않는다.
 */
public final class UsageAggregator {

    private UsageAggregator() {}

    /** @return 날짜 → (instanceId → 사용 분). 사용이 없는 인스턴스는 넣지 않는다. */
    public static Map<LocalDate, Map<String, Long>> dailyMinutes(
            Collection<HourlySample> samples,
            ZoneId zone,
            LocalDate from,
            LocalDate to) {
        Map<LocalDate, Map<String, Long>> result = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            result.put(d, new TreeMap<>());
        }
        for (HourlySample s : samples) {
            LocalDate day = LocalDate.ofInstant(s.hourStart(), zone);
            Map<String, Long> bucket = result.get(day);
            if (bucket == null) {
                continue;
            }
            long minutes = UsageCalculator.usedMinutes(s.sampleCount());
            if (minutes > 0) {
                bucket.merge(s.instanceId(), minutes, Long::sum);
            }
        }
        return result;
    }

    /** 월별 = 일별 합산 (기획서 9절). */
    public static Map<YearMonth, Map<String, Long>> monthlyMinutes(Map<LocalDate, Map<String, Long>> daily) {
        Map<YearMonth, Map<String, Long>> result = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, Map<String, Long>> e : daily.entrySet()) {
            Map<String, Long> bucket = result.computeIfAbsent(YearMonth.from(e.getKey()), k -> new TreeMap<>());
            e.getValue().forEach((instanceId, minutes) -> bucket.merge(instanceId, minutes, Long::sum));
        }
        return result;
    }

    /** 기간 전체 합계: instanceId → 사용 분. */
    public static Map<String, Long> totalMinutes(Map<?, Map<String, Long>> byPeriod) {
        Map<String, Long> total = new TreeMap<>();
        byPeriod.values().forEach(bucket -> bucket.forEach((id, m) -> total.merge(id, m, Long::sum)));
        return total;
    }
}
