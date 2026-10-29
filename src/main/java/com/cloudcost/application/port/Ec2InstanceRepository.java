package com.cloudcost.application.port;

import com.cloudcost.domain.ec2.TrackedInstance;
import java.util.List;
import java.util.UUID;

public interface Ec2InstanceRepository {

    List<TrackedInstance> findAllByOwnerIdAndAccountId(String ownerId, UUID accountId);

    /**
     * (accountId, instanceId)가 같으면 갱신하고 없으면 추가한다.
     */
    void saveAll(String ownerId, UUID accountId, List<TrackedInstance> instances);

    /** 연결 삭제용. @return 지운 행 수 */
    int deleteInstancesByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
