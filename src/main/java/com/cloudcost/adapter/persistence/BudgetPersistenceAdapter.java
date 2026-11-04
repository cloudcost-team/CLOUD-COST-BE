package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.AccountBudgetJpa;
import com.cloudcost.application.port.BudgetRepository;
import com.cloudcost.domain.budget.MonthlyBudget;
import com.cloudcost.domain.cost.DisplayCurrency.Currency;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BudgetPersistenceAdapter implements BudgetRepository {

    private final AccountBudgetJpa budgets;

    BudgetPersistenceAdapter(AccountBudgetJpa budgets) {
        this.budgets = budgets;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MonthlyBudget> findByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return budgets.findByOwnerIdAndAccountId(ownerId, accountId)
            .map(e -> new MonthlyBudget(e.amount, Currency.valueOf(e.currency)));
    }

    @Override
    @Transactional
    public void save(String ownerId, UUID accountId, MonthlyBudget budget, Instant updatedAt) {
        AccountBudgetEntity e = budgets.findByOwnerIdAndAccountId(ownerId, accountId)
            .orElseGet(AccountBudgetEntity::new);
        e.accountId = accountId;
        e.ownerId = ownerId;
        e.amount = budget.amount();
        e.currency = budget.currency().name();
        e.updatedAt = updatedAt;
        budgets.save(e);
    }

    @Override
    @Transactional
    public int deleteByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return budgets.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }
}
