package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.Ec2InstanceJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.Ec2UsageHourlyJpa;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Ec2PersistenceAdapter implements Ec2InstanceRepository, Ec2UsageRepository {

    private final Ec2InstanceJpa instances;
    private final Ec2UsageHourlyJpa usage;

    Ec2PersistenceAdapter(Ec2InstanceJpa instances, Ec2UsageHourlyJpa usage) {
        this.instances = instances;
        this.usage = usage;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrackedInstance> findAllByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return instances.findByOwnerIdAndAccountId(ownerId, accountId).stream()
            .map(Ec2PersistenceAdapter::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public void saveAll(String ownerId, UUID accountId, List<TrackedInstance> tracked) {
        Map<String, Ec2InstanceEntity> existing = instances.findByOwnerIdAndAccountId(ownerId, accountId).stream()
            .collect(Collectors.toMap(e -> e.instanceId, Function.identity()));
        for (TrackedInstance t : tracked) {
            if (!t.ownerId().equals(ownerId) || !t.accountId().equals(accountId)) {
                throw new IllegalArgumentException("다른 owner/account의 인스턴스를 저장할 수 없습니다.");
            }
            Ec2InstanceEntity e = existing.getOrDefault(t.instanceId(), new Ec2InstanceEntity());
            if (e.id == null) {
                e.id = t.id();
            }
            Ec2Instance info = t.info();
            e.ownerId = ownerId;
            e.accountId = accountId;
            e.instanceId = info.instanceId();
            e.name = AccountPersistenceAdapter.truncate(info.name(), 256);
            e.instanceType = info.instanceType();
            e.region = info.region();
            e.availabilityZone = info.availabilityZone();
            e.state = info.state();
            e.launchTime = info.launchTime();
            e.platformDetails = info.platformDetails();
            e.tenancy = info.tenancy();
            e.spot = info.spot();
            e.detailedMonitoring = info.detailedMonitoring();
            e.present = t.present();
            e.activeSinceUsageSync = t.activeSinceUsageSync();
            e.usageCollectedUntil = t.usageCollectedUntil();
            e.firstSeenAt = t.firstSeenAt();
            e.lastSeenAt = t.lastSeenAt();
            instances.save(e);
        }
    }

    @Override
    @Transactional
    public int upsertHourly(String ownerId, UUID accountId, List<HourlySample> samples, Instant collectedAt) {
        Map<String, List<HourlySample>> byInstance =
            samples.stream().collect(Collectors.groupingBy(HourlySample::instanceId));
        int saved = 0;
        for (Map.Entry<String, List<HourlySample>> group : byInstance.entrySet()) {
            Map<Instant, Ec2UsageHourlyEntity> existing = new HashMap<>();
            usage.findByOwnerIdAndAccountIdAndInstanceIdAndHourStartIn(
                    ownerId, accountId, group.getKey(),
                    group.getValue().stream().map(HourlySample::hourStart).toList())
                .forEach(e -> existing.put(e.hourStart, e));
            for (HourlySample s : group.getValue()) {
                Ec2UsageHourlyEntity e = existing.getOrDefault(s.hourStart(), new Ec2UsageHourlyEntity());
                e.ownerId = ownerId;
                e.accountId = accountId;
                e.instanceId = s.instanceId();
                e.hourStart = s.hourStart();
                e.sampleCount = s.sampleCount();
                e.collectedAt = collectedAt;
                usage.save(e);
                saved++;
            }
        }
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HourlySample> findHourly(String ownerId, UUID accountId, Instant start, Instant end) {
        return usage.findByOwnerIdAndAccountIdAndHourStartGreaterThanEqualAndHourStartLessThan(ownerId, accountId,
                start, end)
            .stream()
            .map(e -> new HourlySample(e.instanceId, e.hourStart, e.sampleCount))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> maxSampleCountByInstance(String ownerId, UUID accountId) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (Object[] row : usage.maxSampleCountByInstance(ownerId, accountId)) {
            result.put((String) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    private static TrackedInstance toDomain(Ec2InstanceEntity e) {
        Ec2Instance info = new Ec2Instance(
            e.instanceId, e.name, e.instanceType, e.region, e.availabilityZone, e.state, e.launchTime,
            e.platformDetails, e.tenancy, e.spot, e.detailedMonitoring);
        return new TrackedInstance(
            e.id, e.ownerId, e.accountId, info, e.present, e.activeSinceUsageSync, e.usageCollectedUntil,
            e.firstSeenAt, e.lastSeenAt);
    }
}
