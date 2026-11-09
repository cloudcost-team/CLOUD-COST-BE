package com.cloudcost.adapter.persistence;

import com.cloudcost.adapter.persistence.SpringDataRepositories.RefreshTokenJpa;
import com.cloudcost.application.port.RefreshTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class RefreshTokenPersistenceAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpa tokens;

    RefreshTokenPersistenceAdapter(RefreshTokenJpa tokens) {
        this.tokens = tokens;
    }

    @Override
    @Transactional
    public void save(StoredRefreshToken t) {
        RefreshTokenEntity e = new RefreshTokenEntity();
        e.id = t.id();
        e.ownerId = t.ownerId();
        e.familyId = t.familyId();
        e.tokenHash = t.tokenHash();
        e.createdAt = t.createdAt();
        e.expiresAt = t.expiresAt();
        e.usedAt = t.usedAt();
        e.revokedAt = t.revokedAt();
        tokens.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredRefreshToken> findByHash(String tokenHash) {
        return tokens.findOneByTokenHash(tokenHash).map(e -> new StoredRefreshToken(
                e.id, e.ownerId, e.familyId, e.tokenHash, e.createdAt, e.expiresAt, e.usedAt, e.revokedAt));
    }

    @Override
    @Transactional
    public boolean markUsed(UUID id, Instant now) {
        return tokens.markUsed(id, now) == 1;
    }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, Instant now) {
        tokens.revokeFamily(familyId, now);
    }

    @Override
    @Transactional
    public void deleteExpired(String ownerId, Instant now) {
        tokens.deleteExpired(ownerId, now);
    }
}
