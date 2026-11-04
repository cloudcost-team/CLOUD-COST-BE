package com.cloudcost.domain.usage;

import java.time.Instant;

/**
 * CloudWatch NetworkIn·NetworkOut(Period=3600, Sum) 시간별 합계. 둘 중 하나만 있으면 다른 쪽은 null이다.
 */
public record HourlyNetwork(String instanceId, Instant hourStart, Long inBytes, Long outBytes) {
}
