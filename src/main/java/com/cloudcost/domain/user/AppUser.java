package com.cloudcost.domain.user;

import java.time.Instant;
import java.util.Objects;

/**
 * CloudCost Insight 사용자. id가 모든 사용자 데이터의 owner_id다.
 *
 * <p>Google 로그인 사용자는 (provider=google, providerSubject=Google sub)로 식별한다. 이메일은 표시용이고 식별자로 쓰지 않는다
 * (Google 계정의 이메일은 바뀔 수 있다). 비밀번호는 어디에도 없다.
 */
public record AppUser(
        String id,
        String email,
        String displayName,
        String provider,
        String providerSubject,
        Instant createdAt,
        Instant lastLoginAt) {

    public static final String GOOGLE = "google";

    public AppUser {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
    }

    public static AppUser firstLogin(String id, VerifiedIdentity identity, Instant now) {
        return new AppUser(id, identity.email(), identity.name(), identity.provider(), identity.subject(), now, now);
    }

    /** 로그인할 때마다 Google이 준 최신 이메일·이름으로 갱신한다. */
    public AppUser loggedIn(VerifiedIdentity identity, Instant now) {
        return new AppUser(id, identity.email(), identity.name(), provider, providerSubject, createdAt, now);
    }
}
