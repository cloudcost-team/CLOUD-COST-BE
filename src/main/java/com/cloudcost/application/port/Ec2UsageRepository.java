package com.cloudcost.application.port;

import com.cloudcost.domain.usage.HourlyNetwork;
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
     * 이미 저장된 시간 행에 네트워크 입출력을 채운다. 값이 null인 방향은 그대로 두고, 행이 없는 시간(꺼져 있던 시간)은 건너뛴다.
     *
     * @return 갱신한 행 수
     */
    int updateNetwork(String ownerId, UUID accountId, List<HourlyNetwork> network);

    /**
     * hourStart ∈ [start, end)
     */
    List<HourlySample> findHourly(String ownerId, UUID accountId, Instant start, Instant end);

    /** 연결 삭제용. @return 지운 행 수 */
    int deleteUsageByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
