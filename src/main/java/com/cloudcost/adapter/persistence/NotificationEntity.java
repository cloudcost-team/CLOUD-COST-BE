package com.cloudcost.adapter.persistence;

import com.cloudcost.domain.alert.AlertFacts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class NotificationEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(nullable = false, length = 20)
    String type;

    @Column(nullable = false, length = 500)
    String message;

    @Column(name = "event_key", nullable = false, length = 200)
    String eventKey;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "read_at")
    Instant readAt;

    @Column(name = "threshold_percent")
    Integer thresholdPercent;

    @Column(name = "budget_usd", precision = 20, scale = 6)
    BigDecimal budgetUsd;

    @Column(name = "forecast_usd", precision = 20, scale = 6)
    BigDecimal forecastUsd;

    @Column(name = "spike_date")
    LocalDate spikeDate;

    @Column(name = "spike_ratio", precision = 8, scale = 1)
    BigDecimal spikeRatio;

    @Column(name = "spike_cause", length = 255)
    String spikeCause;

    protected NotificationEntity() {
    }

    AlertFacts facts() {
        return new AlertFacts(thresholdPercent, budgetUsd, forecastUsd, spikeDate, spikeRatio, spikeCause);
    }

    void facts(AlertFacts f) {
        thresholdPercent = f.thresholdPercent();
        budgetUsd = f.budgetUsd();
        forecastUsd = f.forecastUsd();
        spikeDate = f.spikeDate();
        spikeRatio = f.spikeRatio();
        spikeCause = f.causeName() == null || f.causeName().length() <= 255 ? f.causeName() : f.causeName().substring(0, 255);
    }
}
