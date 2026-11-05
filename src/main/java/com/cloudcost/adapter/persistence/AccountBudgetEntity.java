package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_budgets")
class AccountBudgetEntity {

    @Id
    @Column(name = "account_id")
    UUID accountId;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(nullable = false, precision = 15, scale = 2)
    BigDecimal amount;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected AccountBudgetEntity() {
    }
}
