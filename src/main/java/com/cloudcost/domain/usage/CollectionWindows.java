package com.cloudcost.domain.usage;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * CloudWatch 조회 구간 계획.
 *
 * <p>GetMetricStatistics는 응답 1건에 데이터 포인트 1,440개까지라서 30일(=720개) 단위로 나눠 호출한다.
 * 1시간 단위 지표의 보관 기간이 455일이므로 그보다 이전은 요청하지 않는다.
 */
public final class CollectionWindows {

    public static final Duration CHUNK = Duration.ofDays(30);
    public static final Duration MAX_LOOKBACK = Duration.ofDays(455);

    private CollectionWindows() {}

    public record TimeWindow(Instant start, Instant end) {}

    /**
     * @param collectedUntil 이전 수집의 끝. null이면 처음 수집이라 backfill 기간만큼 소급한다.
     * @param overlap 직전 수집의 마지막 시간은 덜 찬 상태였으므로 겹쳐서 다시 읽는다.
     */
    public static List<TimeWindow> plan(Instant collectedUntil, Instant now, Duration backfill, Duration overlap) {
        Instant earliest = now.minus(MAX_LOOKBACK);
        Instant start;
        if (collectedUntil == null || collectedUntil.minus(overlap).isBefore(earliest)) {
            // 소급: 시작을 정시로 올림해 (backfill ÷ 30일)번 호출로 끝낸다 (가장자리 1시간 미만은 포기).
            Instant from = backfill.compareTo(MAX_LOOKBACK) > 0 ? earliest : now.minus(backfill);
            Instant floor = from.truncatedTo(ChronoUnit.HOURS);
            start = floor.equals(from) ? floor : floor.plus(Duration.ofHours(1));
        } else {
            // 증분: 직전 수집의 마지막(덜 찬) 시간부터 다시 읽도록 정시로 내림한다.
            start = collectedUntil.minus(overlap).truncatedTo(ChronoUnit.HOURS);
        }
        List<TimeWindow> windows = new ArrayList<>();
        while (start.isBefore(now)) {
            Instant end = start.plus(CHUNK);
            if (end.isAfter(now)) {
                end = now;
            }
            windows.add(new TimeWindow(start, end));
            start = end;
        }
        return windows;
    }
}
