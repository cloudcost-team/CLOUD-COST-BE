package com.cloudcost.application.port;

import com.cloudcost.domain.sync.SyncJob;
import java.util.List;
import java.util.UUID;

public interface SyncJobRepository {

    SyncJob save(SyncJob job);

    List<SyncJob> findRecentByOwnerIdAndAccountId(String ownerId, UUID accountId, int limit);
}
