package com.cloudcost.application.port;

import com.cloudcost.domain.budget.MonthlyBudget;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 연결별 월 예산. 연결 하나에 예산 하나 */
public interface BudgetRepository {

    Optional<MonthlyBudget> findByOwnerIdAndAccountId(String ownerId, UUID accountId);

    /** 있으면 바꾸고 없으면 만든다 */
    void save(String ownerId, UUID accountId, MonthlyBudget budget, Instant updatedAt);

    /** @return 지운 행 수 (0 또는 1) */
    int deleteByOwnerIdAndAccountId(String ownerId, UUID accountId);
}
