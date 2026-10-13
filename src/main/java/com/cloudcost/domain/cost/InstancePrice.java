package com.cloudcost.domain.cost;

import java.math.BigDecimal;

/**
 * 인스턴스 1대에 적용할 온디맨드 시간당 단가.
 *
 * @param hourlyUsd 단가를 못 구하면 null (0으로 대체하지 않는다)
 * @param note      단가가 없거나 Spot인 경우의 안내
 */
public record InstancePrice(String instanceId, BigDecimal hourlyUsd, boolean spot, String note) {

    public static InstancePrice of(String instanceId, BigDecimal hourlyUsd, boolean spot) {
        if (hourlyUsd == null || hourlyUsd.signum() <= 0) {
            return unpriced(instanceId, spot, CostNotes.PRICE_NOT_FOUND);
        }
        return new InstancePrice(instanceId, hourlyUsd, spot, spot ? CostNotes.SPOT : null);
    }

    public static InstancePrice unpriced(String instanceId, boolean spot, String reason) {
        return new InstancePrice(instanceId, null, spot, reason);
    }

    public boolean priced() {
        return hourlyUsd != null;
    }
}
