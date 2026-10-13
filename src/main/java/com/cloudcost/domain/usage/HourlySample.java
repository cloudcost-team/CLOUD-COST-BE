package com.cloudcost.domain.usage;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * CloudWatch GetMetricStatistics(Period=3600, Statistic=SampleCount) 데이터 포인트 1개.
 * 인스턴스가 켜져 있던 시간에만 데이터 포인트가 생긴다.
 */
public record HourlySample(String instanceId, Instant hourStart, BigDecimal sampleCount) {}
