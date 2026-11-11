package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.AccountResourceJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.AccountResourceScanJpa;
import com.cloudcost.application.port.AccountResourceRepository;
import com.cloudcost.domain.resource.AwsResource;
import com.cloudcost.domain.resource.ResourceScan;
import com.cloudcost.domain.resource.ResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 방치 리소스 후보와 확장 수집 상태 (ADR-020) */
@Component
class AccountResourcePersistenceAdapter implements AccountResourceRepository {

    private final AccountResourceJpa resources;
    private final AccountResourceScanJpa scans;

    AccountResourcePersistenceAdapter(AccountResourceJpa resources, AccountResourceScanJpa scans) {
        this.resources = resources;
        this.scans = scans;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResourceScan> findScanByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return scans.findByOwnerIdAndAccountId(ownerId, accountId)
            .map(e -> new ResourceScan(ResourceScan.Status.valueOf(e.status), e.permissionVersion, e.scannedAt,
                e.message));
    }

    @Override
    @Transactional
    public void saveScan(String ownerId, UUID accountId, ResourceScan scan) {
        AccountResourceScanEntity e = scans.findByOwnerIdAndAccountId(ownerId, accountId)
            .orElseGet(AccountResourceScanEntity::new);
        e.accountId = accountId;
        e.ownerId = ownerId;
        e.status = scan.status().name();
        e.permissionVersion = scan.permissionVersion();
        e.scannedAt = scan.scannedAt();
        e.message = scan.message() == null || scan.message().length() <= 500
            ? scan.message() : scan.message().substring(0, 500);
        scans.save(e);
    }

    @Override
    @Transactional
    public void replaceAll(
        String ownerId, UUID accountId, List<AwsResource> list, ResourceScan scan, Instant observedAt) {
        resources.deleteByOwnerIdAndAccountId(ownerId, accountId);
        for (AwsResource r : list) {
            AccountResourceEntity e = new AccountResourceEntity();
            e.id = UUID.randomUUID();
            e.ownerId = ownerId;
            e.accountId = accountId;
            e.type = r.type().name();
            e.resourceId = truncate(r.resourceId(), 255);
            e.name = truncate(r.name(), 255);
            e.region = r.region();
            e.subtype = truncate(r.subtype(), 64);
            e.sizeGb = r.sizeGb();
            e.inUse = r.inUse();
            e.createdAt = r.createdAt();
            e.requestCount = r.requestCount();
            e.platform = truncate(r.platform(), 100);
            e.observedAt = observedAt;
            resources.save(e);
        }
        saveScan(ownerId, accountId, scan);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AwsResource> findAllByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return resources.findByOwnerIdAndAccountIdOrderByRegionAscTypeAscResourceIdAsc(ownerId, accountId).stream()
            .map(e -> new AwsResource(ResourceType.valueOf(e.type), e.resourceId, e.name, e.region, e.subtype,
                e.sizeGb, e.inUse, e.createdAt, e.requestCount, e.platform))
            .toList();
    }

    @Override
    @Transactional
    public void deleteByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        resources.deleteByOwnerIdAndAccountId(ownerId, accountId);
        scans.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
