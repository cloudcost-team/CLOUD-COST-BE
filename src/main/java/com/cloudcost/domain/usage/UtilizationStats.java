package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 인스턴스 1대의 최근 14일 사용률 요약 (낭비 탐지·벤치마킹의 판단 근거).
 *
 * <ul>
 *   <li>CPU 평균: 시간별 평균을 켜져 있던 분(SampleCount)으로 가중 평균
 *   <li>CPU P95: 시간별 평균 값들의 95번째 백분위 (nearest-rank). 1분 단위 P95가 아니라 "시간 평균의 P95"다
 *   <li>CPU 최대: 시간별 최대의 최댓값
 *   <li>네트워크: 입력 + 출력 합계 (바이트). 켜져 있던 시간 중 하나라도 값이 비어 있으면 null (모름)
 * </ul>
 * 꺼져 있던 시간은 데이터 포인트가 없으므로 계산에 들어가지 않는다 (켜져 있는 동안의 사용률).
 *
 * @param cpuHours     CPU 평균이 있는 시간 수. 0이면 CPU 값은 모두 null
 * @param networkHours 네트워크 값이 있는 시간 수. 0이면 networkBytes는 null
 */
public record UtilizationStats(
    String instanceId,
    int cpuHours,
    BigDecimal cpuAvg,
    BigDecimal cpuP95,
    BigDecimal cpuMax,
    int networkHours,
    Long networkBytes) {

    /** 판단에 쓰는 기간 */
    public static final Duration WINDOW = Duration.ofDays(14);

    private static final int SCALE = 2;

    /**
     * @param samples 같은 인스턴스든 여러 인스턴스든 상관없다. 기간은 호출하는 쪽이 정한다 (보통 최근 14일)
     * @return instanceId → 요약. 샘플이 하나도 없는 인스턴스는 들어가지 않는다
     */
    public static Map<String, UtilizationStats> byInstance(List<HourlySample> samples) {
        Map<String, List<HourlySample>> grouped = new LinkedHashMap<>();
        samples.forEach(s -> grouped.computeIfAbsent(s.instanceId(), k -> new ArrayList<>()).add(s));
        Map<String, UtilizationStats> result = new LinkedHashMap<>();
        grouped.forEach((id, list) -> result.put(id, of(id, list)));
        return result;
    }

    static UtilizationStats of(String instanceId, List<HourlySample> samples) {
        List<HourlySample> cpu = samples.stream().filter(s -> s.cpuAvg() != null).toList();
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal minutes = BigDecimal.ZERO;
        for (HourlySample s : cpu) {
            BigDecimal weight = s.sampleCount() == null || s.sampleCount().signum() <= 0 ? BigDecimal.ONE : s.sampleCount();
            weighted = weighted.add(s.cpuAvg().multiply(weight));
            minutes = minutes.add(weight);
        }
        BigDecimal avg = cpu.isEmpty() ? null : weighted.divide(minutes, SCALE, RoundingMode.HALF_UP);
        BigDecimal p95 = cpu.isEmpty() ? null : percentile95(cpu.stream().map(HourlySample::cpuAvg).toList());
        BigDecimal max = cpu.stream()
            .map(s -> s.cpuMax() != null ? s.cpuMax() : s.cpuAvg())
            .max(Comparator.naturalOrder())
            .map(v -> v.setScale(SCALE, RoundingMode.HALF_UP))
            .orElse(null);

        // 켜져 있던 시간 중 하나라도 입력·출력 네트워크가 비어 있으면 합계를 모르는 것으로 본다
        // (일부만 더하면 실제보다 적게 잡혀 쓰는 서버가 유휴로 보일 수 있다)
        int networkHours = 0;
        long networkBytes = 0;
        boolean complete = true;
        for (HourlySample s : samples) {
            if (UsageCalculator.usedMinutes(s.sampleCount()) <= 0) {
                continue;
            }
            if (s.networkInBytes() == null || s.networkOutBytes() == null) {
                complete = false;
                continue;
            }
            networkHours++;
            networkBytes += s.networkInBytes() + s.networkOutBytes();
        }
        return new UtilizationStats(instanceId, cpu.size(), avg, p95, max, networkHours,
            complete && networkHours > 0 ? networkBytes : null);
    }

    /** nearest-rank: 정렬한 값의 ceil(0.95 × n)번째 */
    static BigDecimal percentile95(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
