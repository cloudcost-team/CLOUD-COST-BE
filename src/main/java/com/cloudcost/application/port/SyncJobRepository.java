package com.cloudcost.application.port;

import com.cloudcost.domain.sync.SyncJob;
import java.util.List;
import java.util.UUID;

public interface SyncJobRepository {

    SyncJob save(SyncJob job);

    List<SyncJob> findRecentByOwnerIdAndAccountId(String ownerId, UUID accountId, int limit);

    /** 연결 삭제용. @return 지운 행 수 */
    int deleteAllByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
