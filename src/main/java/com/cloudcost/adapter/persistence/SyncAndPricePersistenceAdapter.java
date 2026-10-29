package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.PriceCacheJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.SyncJobJpa;
import com.cloudcost.application.port.PriceCacheRepository;
import com.cloudcost.application.port.SyncJobRepository;
import com.cloudcost.application.port.UnitOfWork;
import com.cloudcost.domain.cost.PriceQuery;
import com.cloudcost.domain.sync.SyncJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SyncAndPricePersistenceAdapter implements SyncJobRepository, PriceCacheRepository, UnitOfWork {

    private final SyncJobJpa jobs;
    private final PriceCacheJpa prices;
    private final TransactionTemplate tx;

    SyncAndPricePersistenceAdapter(SyncJobJpa jobs, PriceCacheJpa prices, TransactionTemplate tx) {
        this.jobs = jobs;
        this.prices = prices;
        this.tx = tx;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    @Override
    @Transactional
    public SyncJob save(SyncJob job) {
        SyncJobEntity e = new SyncJobEntity();
        e.id = job.id();
        e.ownerId = job.ownerId();
        e.accountId = job.accountId();
        e.triggerType = job.trigger().name();
        e.status = job.status().name();
        e.instancesRefreshed = job.instancesRefreshed();
        e.usageRefreshed = job.usageRefreshed();
        e.ec2RequestCount = job.ec2RequestCount();
        e.cwRequestCount = job.cwRequestCount();
        e.instanceCount = job.instanceCount();
        e.usageRows = job.usageRows();
        e.errorCode = job.errorCode();
        e.errorMessage = AccountPersistenceAdapter.truncate(job.errorMessage(), 1000);
        e.warning = AccountPersistenceAdapter.truncate(job.warning(), 2000);
        e.startedAt = job.startedAt();
        e.finishedAt = job.finishedAt();
        jobs.save(e);
        return job;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncJob> findRecentByOwnerIdAndAccountId(String ownerId, UUID accountId, int limit) {
        return jobs.findByOwnerIdAndAccountIdOrderByStartedAtDesc(ownerId, accountId, Limit.of(limit)).stream()
            .map(e -> new SyncJob(
                e.id, e.ownerId, e.accountId, SyncJob.Trigger.valueOf(e.triggerType),
                SyncJob.Status.valueOf(e.status), e.instancesRefreshed, e.usageRefreshed, e.ec2RequestCount,
                e.cwRequestCount, e.instanceCount, e.usageRows, e.errorCode, e.errorMessage, e.warning,
                e.startedAt, e.finishedAt))
            .toList();
    }

    @Override
    @Transactional
    public int deleteAllByOwnerIdAndAccountId(String ownerId, UUID accountId) {
        return jobs.deleteByOwnerIdAndAccountId(ownerId, accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CachedPrice> find(PriceQuery q) {
        return lookup(q).map(e -> new CachedPrice(e.usdPerHour, e.fetchedAt));
    }

    @Override
    @Transactional
    public void save(PriceQuery q, BigDecimal usdPerHour, Instant fetchedAt) {
        Ec2PriceCacheEntity e = lookup(q).orElseGet(Ec2PriceCacheEntity::new);
        if (e.id == null) {
            e.id = UUID.randomUUID();
        }
        e.region = q.region();
        e.instanceType = q.instanceType();
        e.operatingSystem = q.operatingSystem();
        e.tenancy = q.tenancy();
        e.licenseModel = q.licenseModel();
        e.preInstalledSw = q.preInstalledSw();
        e.usdPerHour = usdPerHour;
        e.fetchedAt = fetchedAt;
        prices.save(e);
    }

    private Optional<Ec2PriceCacheEntity> lookup(PriceQuery q) {
        return prices.findByRegionAndInstanceTypeAndOperatingSystemAndTenancyAndLicenseModelAndPreInstalledSw(
            q.region(), q.instanceType(), q.operatingSystem(), q.tenancy(), q.licenseModel(), q.preInstalledSw());
    }
}
