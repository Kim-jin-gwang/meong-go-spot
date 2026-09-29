package com.meonggo.backend.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class AuthSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 22)
    private String refreshTokenSelector;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String refreshTokenHash;

    private java.util.UUID pushInstallationId;

    @Column(length = 20)
    private String pushPlatform;

    @Column(columnDefinition = "text")
    private String pushTokenCiphertext;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String pushTokenLookupHash;

    private Instant pushLastSeenAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    protected AuthSession() {}

    public AuthSession(
            long memberId, String selector, String hash, Instant now, Instant expiresAt) {
        this.memberId = memberId;
        this.refreshTokenSelector = selector;
        this.refreshTokenHash = hash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getRefreshTokenSelector() {
        return refreshTokenSelector;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void rotate(String hash, Instant now) {
        refreshTokenHash = hash;
        lastUsedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }
}
