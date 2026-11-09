package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 토큰 원문은 없다. SHA-256 해시만 저장한다. */
@Entity
@Table(name = "refresh_tokens")
class RefreshTokenEntity {

    @Id
    UUID id;

    @Column(name = "owner_id", nullable = false, length = 64)
    String ownerId;

    @Column(name = "family_id", nullable = false)
    UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    String tokenHash;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "used_at")
    Instant usedAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    protected RefreshTokenEntity() {
    }
}
