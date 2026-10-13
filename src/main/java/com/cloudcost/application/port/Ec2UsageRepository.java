package com.cloudcost.application.port;

import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface Ec2UsageRepository {

    /**
     * (accountId, instanceId, hourStart)가 같으면 갱신, 없으면 추가. @return 저장한 행 수
     */
    int upsertHourly(String ownerId, UUID accountId, List<HourlySample> samples, Instant collectedAt);

    /**
     * hourStart ∈ [start, end)
     */
    List<HourlySample> findHourly(String ownerId, UUID accountId, Instant start, Instant end);
}
