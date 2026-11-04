package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "alert_settings")
class AlertSettingsEntity {

    @Id
    @Column(name = "owner_id", length = 64)
    String ownerId;

    @Column(nullable = false)
    boolean budget80;

    @Column(nullable = false)
    boolean budget100;

    @Column(nullable = false)
    boolean budget120;

    @Column(nullable = false)
    boolean spike;

    @Column(name = "weekly_summary", nullable = false)
    boolean weeklySummary;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected AlertSettingsEntity() {
    }
}
