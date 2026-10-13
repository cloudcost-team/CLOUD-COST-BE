package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.AwsAccountJpa;
import com.cloudcost.adapter.persistence.SpringDataRepositories.UserJpa;
import com.cloudcost.application.port.AwsAccountRepository;
import com.cloudcost.application.port.UserRepository;
import com.cloudcost.domain.account.AwsAccount;
import com.cloudcost.domain.account.AwsRegion;
import com.cloudcost.domain.account.ExternalId;
import com.cloudcost.domain.account.RoleArn;
import com.cloudcost.domain.user.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AccountPersistenceAdapter implements AwsAccountRepository, UserRepository {

    private final AwsAccountJpa accounts;
    private final UserJpa users;

    AccountPersistenceAdapter(AwsAccountJpa accounts, UserJpa users) {
        this.accounts = accounts;
        this.users = users;
    }

    @Override
    @Transactional
    public void ensureExists(String ownerId, Instant now) {
        if (!users.existsById(ownerId)) {
            users.save(new UserEntity(ownerId, now));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> findByOwnerId(String ownerId) {
        return users.findOneById(ownerId).map(AccountPersistenceAdapter::toUser);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> findByProvider(String provider, String providerSubject) {
        return users.findByAuthProviderAndProviderSubject(provider, providerSubject)
            .map(AccountPersistenceAdapter::toUser);
    }

    @Override
    @Transactional
    public AppUser save(AppUser user) {
        UserEntity e = users.findOneById(user.id()).orElseGet(() -> new UserEntity(user.id(), user.createdAt()));
        e.email = truncate(user.email(), 320);
        e.displayName = truncate(user.displayName(), 200);
        e.authProvider = user.provider();
        e.providerSubject = user.providerSubject();
        e.lastLoginAt = user.lastLoginAt();
        return toUser(users.save(e));
    }

    private static AppUser toUser(UserEntity e) {
        return new AppUser(e.id, e.email, e.displayName, e.authProvider, e.providerSubject, e.createdAt, e.lastLoginAt);
    }

    @Override
    @Transactional
    public AwsAccount save(AwsAccount account) {
        AwsAccountEntity e = accounts.findByOwnerIdAndId(account.ownerId(), account.id())
            .orElseGet(AwsAccountEntity::new);
        e.id = account.id();
        e.ownerId = account.ownerId();
        e.region = account.region().value();
        e.externalId = account.externalId().value();
        e.roleArn = account.roleArn() == null ? null : account.roleArn().value();
        e.awsAccountId = account.roleArn() == null ? null : account.roleArn().accountId();
        e.status = account.status().name();
        e.lastErrorCode = account.lastErrorCode();
        e.lastErrorMessage = truncate(account.lastErrorMessage(), 1000);
        e.verifiedAt = account.verifiedAt();
        e.instancesRefreshedAt = account.instancesRefreshedAt();
        e.usageRefreshedAt = account.usageRefreshedAt();
        e.stackId = truncate(account.stackId(), 512);
        e.disconnectedAt = account.disconnectedAt();
        e.createdAt = account.createdAt();
        e.updatedAt = account.updatedAt();
        return toDomain(accounts.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AwsAccount> findByOwnerIdAndId(String ownerId, UUID id) {
        return accounts.findByOwnerIdAndId(ownerId, id).map(AccountPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AwsAccount> findAllByOwnerId(String ownerId) {
        return accounts.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
            .map(AccountPersistenceAdapter::toDomain)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveByOwnerIdAndAwsAccount(String ownerId, String awsAccountId, String region,
                                                      UUID excludeId) {
        return accounts.existsByOwnerIdAndAwsAccountIdAndRegionAndStatusAndIdNot(
            ownerId, awsAccountId, region, AwsAccount.Status.ACTIVE.name(), excludeId);
    }

    /** 스택 신호 처리 전용: 신호에는 owner가 없으므로 External ID로 찾고, owner는 찾은 행에서 읽는다. */
    @Override
    @Transactional(readOnly = true)
    public Optional<AwsAccount> findByExternalIdForCallback(String externalId) {
        return accounts.findByExternalId(externalId).map(AccountPersistenceAdapter::toDomain);
    }

    private static AwsAccount toDomain(AwsAccountEntity e) {
        return new AwsAccount(
            e.id,
            e.ownerId,
            new AwsRegion(e.region),
            new ExternalId(e.externalId),
            e.roleArn == null ? null : RoleArn.parse(e.roleArn),
            AwsAccount.Status.valueOf(e.status),
            e.lastErrorCode,
            e.lastErrorMessage,
            e.verifiedAt,
            e.instancesRefreshedAt,
            e.usageRefreshedAt,
            e.stackId,
            e.disconnectedAt,
            e.createdAt,
            e.updatedAt);
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
