package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ec2_usage_hourly")
class Ec2UsageHourlyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(name = "instance_id", nullable = false, length = 32)
    String instanceId;

    @Column(name = "hour_start", nullable = false)
    Instant hourStart;

    @Column(name = "sample_count", nullable = false, precision = 10, scale = 2)
    BigDecimal sampleCount;

    @Column(name = "collected_at", nullable = false)
    Instant collectedAt;

    protected Ec2UsageHourlyEntity() {
    }
}
