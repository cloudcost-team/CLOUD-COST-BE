package com.cloudcost.application.port;

import com.cloudcost.domain.user.AppUser;
import java.time.Instant;
import java.util.Optional;

public interface UserRepository {

    /**
     * 첫 요청에서 사용자 행을 만든다. 이미 있으면 아무것도 하지 않는다 (로컬 DevAuth 사용자용).
     */
    void ensureExists(String ownerId, Instant now);

    /** 로그인한 본인 정보 (ownerId = 본인 id) */
    Optional<AppUser> findByOwnerId(String ownerId);

    Optional<AppUser> findByProvider(String provider, String providerSubject);

    AppUser save(AppUser user);
}
