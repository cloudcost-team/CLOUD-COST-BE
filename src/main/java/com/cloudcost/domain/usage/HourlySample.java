package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CloudWatch GetMetricStatistics(Period=3600) 시간별 데이터 포인트 1개.
 * 인스턴스가 켜져 있던 시간에만 데이터 포인트가 생긴다.
 *
 * @param sampleCount     CPUUtilization SampleCount = 그 시간에 켜져 있던 분 (사용 시간 계산용)
 * @param cpuAvg          CPUUtilization Average (%). 지표 수집 전에 저장한 행이면 null
 * @param cpuMax          CPUUtilization Maximum (%). 지표 수집 전에 저장한 행이면 null
 * @param networkInBytes  NetworkIn Sum (바이트). 최근 14일만 수집하므로 그 밖의 시간은 null
 * @param networkOutBytes NetworkOut Sum (바이트). 최근 14일만 수집하므로 그 밖의 시간은 null
 */
public record HourlySample(
    String instanceId,
    Instant hourStart,
    BigDecimal sampleCount,
    BigDecimal cpuAvg,
    BigDecimal cpuMax,
    Long networkInBytes,
    Long networkOutBytes) {

    /** 사용 시간만 있는 샘플 (CPU·네트워크 지표 없음) */
    public HourlySample(String instanceId, Instant hourStart, BigDecimal sampleCount) {
        this(instanceId, hourStart, sampleCount, null, null, null, null);
    }

    /** CPU 지표까지 있는 샘플 (네트워크 없음) */
    public static HourlySample cpu(
        String instanceId, Instant hourStart, BigDecimal sampleCount, BigDecimal cpuAvg, BigDecimal cpuMax) {
        return new HourlySample(instanceId, hourStart, sampleCount, cpuAvg, cpuMax, null, null);
    }
}
