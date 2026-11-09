package com.cloudcost.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 리프레시 토큰 저장소. 원문은 저장하지 않고 SHA-256 해시만 둔다 (ADR-017). */
public interface RefreshTokenRepository {

    void save(StoredRefreshToken token);

    Optional<StoredRefreshToken> findByHash(String tokenHash);

    /** 아직 쓰지 않았고 무효화되지 않은 토큰만 사용 처리한다. @return 이번 호출이 사용 처리했으면 true (동시 사용 방지) */
    boolean markUsed(UUID id, Instant now);

    /** 같은 로그인에서 이어진 토큰 전체를 무효화한다 (재사용 감지, 로그아웃) */
    void revokeFamily(UUID familyId, Instant now);

    /** 만료된 토큰 행을 지운다 */
    void deleteExpired(String ownerId, Instant now);

    /**
     * @param familyId 한 번의 로그인에서 rotation으로 이어지는 토큰 묶음
     */
    record StoredRefreshToken(
            UUID id, String ownerId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt,
            Instant usedAt, Instant revokedAt) {}
}
