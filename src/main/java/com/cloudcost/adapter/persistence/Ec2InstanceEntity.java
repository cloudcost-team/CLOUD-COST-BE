package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 저장 허용 필드만 둔다. IP·키페어·보안그룹·전체 태그·사용자 데이터는 저장하지 않는다.
 */
@Entity
@Table(name = "ec2_instances")
class Ec2InstanceEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "account_id", nullable = false)
    UUID accountId;

    @Column(name = "instance_id", nullable = false, length = 32)
    String instanceId;

    @Column(length = 256)
    String name;

    @Column(name = "instance_type", nullable = false, length = 64)
    String instanceType;

    @Column(nullable = false, length = 32)
    String region;

    @Column(name = "availability_zone", length = 32)
    String availabilityZone;

    @Column(nullable = false, length = 32)
    String state;

    @Column(name = "launch_time")
    Instant launchTime;

    @Column(name = "platform_details", length = 128)
    String platformDetails;

    @Column(length = 16)
    String tenancy;

    @Column(nullable = false)
    boolean spot;

    @Column(name = "detailed_monitoring", nullable = false)
    boolean detailedMonitoring;

    @Column(nullable = false)
    boolean present;

    @Column(name = "active_since_usage_sync", nullable = false)
    boolean activeSinceUsageSync;

    @Column(name = "usage_collected_until")
    Instant usageCollectedUntil;

    @Column(name = "first_seen_at", nullable = false)
    Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    Instant lastSeenAt;

    protected Ec2InstanceEntity() {
    }
}
