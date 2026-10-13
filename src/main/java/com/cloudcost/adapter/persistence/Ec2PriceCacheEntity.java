package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ec2_price_cache")
class Ec2PriceCacheEntity {

    @Id
    UUID id;

    @Column(nullable = false, length = 32)
    String region;

    @Column(name = "instance_type", nullable = false, length = 64)
    String instanceType;

    @Column(name = "operating_system", nullable = false, length = 64)
    String operatingSystem;

    @Column(nullable = false, length = 16)
    String tenancy;

    @Column(name = "license_model", nullable = false, length = 64)
    String licenseModel;

    @Column(name = "pre_installed_sw", nullable = false, length = 32)
    String preInstalledSw;

    @Column(name = "usd_per_hour", precision = 14, scale = 8)
    BigDecimal usdPerHour;

    @Column(name = "fetched_at", nullable = false)
    Instant fetchedAt;

    protected Ec2PriceCacheEntity() {
    }
}
