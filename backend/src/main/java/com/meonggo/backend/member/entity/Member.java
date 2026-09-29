package com.meonggo.backend.member.entity;

import com.meonggo.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class Member extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String loginId;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 30)
    private String nickname;

    @Column(columnDefinition = "text")
    private String phoneCiphertext;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String phoneLookupHash;

    private Instant phoneVerifiedAt;

    private Boolean privacyCollectionAgreed;

    @Column(length = 50)
    private String privacyCollectionPolicyVersion;

    private Instant privacyCollectionConsentedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    private Instant deletedAt;

    protected Member() {}

    public Member(
            String loginId,
            String passwordHash,
            String nickname,
            String phoneCiphertext,
            String phoneLookupHash,
            Instant phoneVerifiedAt,
            Boolean privacyCollectionAgreed,
            String consentVersion,
            Instant consentedAt) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.phoneCiphertext = phoneCiphertext;
        this.phoneLookupHash = phoneLookupHash;
        this.phoneVerifiedAt = phoneVerifiedAt;
        this.privacyCollectionAgreed = privacyCollectionAgreed;
        this.privacyCollectionPolicyVersion = consentVersion;
        this.privacyCollectionConsentedAt = consentedAt;
        this.status = MemberStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getLoginId() {
        return loginId;
    }

    public String getNickname() {
        return nickname;
    }

    /** 계정 찾기(A9)가 복구 증명의 범위와 대조할 때만 쓴다. 응답·로그로 나가지 않는다. */
    public String getPhoneLookupHash() {
        return phoneLookupHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public void changeNickname(String normalizedNickname) {
        this.nickname = normalizedNickname;
    }

    public void changePassword(String encodedPasswordHash) {
        this.passwordHash = encodedPasswordHash;
    }

    /** A5 탈퇴 — 상태만 바꾼다. 보호값 파기는 30일 뒤 별도 작업이다 (docs/backend-security-operations-policy.md). */
    public void withdraw(Instant now) {
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = now;
    }
}
