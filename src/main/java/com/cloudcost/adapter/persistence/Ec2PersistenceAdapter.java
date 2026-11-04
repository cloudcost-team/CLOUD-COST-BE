package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.Ec2InstanceJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.Ec2UsageHourlyJpa;
import com.cloudcost.application.port.Ec2InstanceRepository;
import com.cloudcost.application.port.Ec2UsageRepository;
import com.cloudcost.domain.ec2.Ec2Instance;
import com.cloudcost.domain.ec2.TrackedInstance;
import com.cloudcost.domain.usage.HourlyNetwork;
import com.cloudcost.domain.usage.HourlySample;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
            e.metricsCollectedUntil = t.metricsCollectedUntil();
            instances.save(e);
        }
    }

    /**
     * 같은 (계정, 인스턴스, 시각)이 있으면 값을 갱신하고 없으면 추가한다. 겹친 구간을 다시 읽어도 행이 늘지 않는다.
     * 한 번에 들어온 목록 안의 중복 시각은 마지막 값 하나만 쓴다 (UNIQUE 위반 방지).
     * 같은 계정의 수집은 Ec2DataRefresher가 계정 단위로 잠가 한 번에 하나만 쓴다.
     */
    @Override
    @Transactional
    public int upsertHourly(String ownerId, UUID accountId, List<HourlySample> samples, Instant collectedAt) {
        Map<String, Map<Instant, HourlySample>> byInstance = new LinkedHashMap<>();
        for (HourlySample s : samples) {
            byInstance.computeIfAbsent(s.instanceId(), k -> new LinkedHashMap<>()).put(s.hourStart(), s);
        }
        int saved = 0;
        for (Map.Entry<String, Map<Instant, HourlySample>> group : byInstance.entrySet()) {
            Map<Instant, Ec2UsageHourlyEntity> existing = new HashMap<>();
            usage.findByOwnerIdAndAccountIdAndInstanceIdAndHourStartIn(
                    ownerId, accountId, group.getKey(),
                    List.copyOf(group.getValue().keySet()))
                .forEach(e -> existing.put(e.hourStart, e));
            for (HourlySample s : group.getValue().values()) {
                Ec2UsageHourlyEntity e = existing.getOrDefault(s.hourStart(), new Ec2UsageHourlyEntity());
                e.ownerId = ownerId;
                e.accountId = accountId;
                e.instanceId = s.instanceId();
                e.hourStart = s.hourStart();
                e.sampleCount = s.sampleCount();
                if (s.cpuAvg() != null) {
                    e.cpuAvg = s.cpuAvg();
                }
                if (s.cpuMax() != null) {
                    e.cpuMax = s.cpuMax();
                }
                e.collectedAt = collectedAt;
                usage.save(e);
                saved++;
            }
        }
        return saved;
    }

    @Override
    @Transactional
    public int updateNetwork(String ownerId, UUID accountId, List<HourlyNetwork> network) {
        Map<String, List<HourlyNetwork>> byInstance = network.stream()
            .collect(Collectors.groupingBy(HourlyNetwork::instanceId, LinkedHashMap::new, Collectors.toList()));
        int updated = 0;
        for (Map.Entry<String, List<HourlyNetwork>> group : byInstance.entrySet()) {
            Map<Instant, Ec2UsageHourlyEntity> existing = new HashMap<>();
            usage.findByOwnerIdAndAccountIdAndInstanceIdAndHourStartIn(
                    ownerId, accountId, group.getKey(),
                    group.getValue().stream().map(HourlyNetwork::hourStart).distinct().toList())
                .forEach(e -> existing.put(e.hourStart, e));
            for (HourlyNetwork n : group.getValue()) {
                Ec2UsageHourlyEntity e = existing.get(n.hourStart());
                if (e == null) {
                    continue;
                }
                if (n.inBytes() != null) {
                    e.networkInBytes = n.inBytes();
                }
                if (n.outBytes() != null) {
                    e.networkOutBytes = n.outBytes();
                }
                usage.save(e);
                updated++;
            }
        }
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HourlySample> findHourly(String ownerId, UUID accountId, Instant start, Instant end) {
        return usage.findByOwnerIdAndAccountIdAndHourStartGreaterThanEqualAndHourStartLessThan(ownerId, accountId,
                start, end)
            .stream()
            .map(e -> new HourlySample(e.instanceId, e.hourStart, e.sampleCount, e.cpuAvg, e.cpuMax,
                e.networkInBytes, e.networkOutBytes))
            .toList();
    }

    @Override
    @Transactional
    public int deleteInstancesByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return instances.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    @Override
    @Transactional
    public int deleteUsageByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return usage.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    private static TrackedInstance toDomain(Ec2InstanceEntity e) {
        Ec2Instance info = new Ec2Instance(
            e.instanceId, e.name, e.instanceType, e.region, e.availabilityZone, e.state, e.launchTime,
            e.platformDetails, e.tenancy, e.spot, e.detailedMonitoring);
        return new TrackedInstance(
            e.id, e.ownerId, e.accountId, info, e.present, e.activeSinceUsageSync, e.usageCollectedUntil,
            e.firstSeenAt, e.lastSeenAt, e.metricsCollectedUntil);
    }
}
