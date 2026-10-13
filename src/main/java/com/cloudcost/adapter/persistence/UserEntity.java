package com.cloudcost.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 비밀번호 컬럼은 없다. Google 로그인 사용자는 Google sub와 표시용 이메일·이름만 저장한다. */
@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @Column(length = 64)
    String id;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(length = 320)
    String email;

    @Column(name = "display_name", length = 200)
    String displayName;

    @Column(name = "auth_provider", length = 16)
    String authProvider;

    @Column(name = "provider_subject", length = 255)
    String providerSubject;

    @Column(name = "last_login_at")
    Instant lastLoginAt;

    protected UserEntity() {
    }

    UserEntity(String id, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }
}
