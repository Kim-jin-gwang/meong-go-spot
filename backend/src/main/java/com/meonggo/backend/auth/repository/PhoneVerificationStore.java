package com.meonggo.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;

/** Redis 경계. 키와 값에는 원문 대신 목적별 hash와 서버 생성 식별자만 전달한다. */
public interface PhoneVerificationStore {
    long reserveSend(String phoneHash, String ipHash, String requestId);

    boolean activateOtp(String phoneHash, String requestId, String verificationId, String otpHash);

    Optional<OtpSnapshot> findOtp(String phoneHash);

    boolean confirmOtp(
            String phoneHash,
            String verificationId,
            String expectedHash,
            boolean matched,
            ProofRecord proof);

    Optional<ProofSnapshot> findProof(String selector);

    boolean claimProof(String selector, String secretHash, String phoneHash, String requestId);

    void restoreProof(String selector, String requestId);

    void consumeProof(String selector, String requestId);

    record OtpSnapshot(String verificationId, String hash) {
        @Override
        public String toString() {
            return "OtpSnapshot[redacted]";
        }
    }

    record ProofRecord(
            String selector,
            String secretHash,
            String phoneLookupHash,
            Instant issuedAt,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "ProofRecord[redacted]";
        }
    }

    record ProofSnapshot(
            String secretHash,
            String phoneLookupHash,
            String status,
            Instant issuedAt,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "ProofSnapshot[redacted]";
        }
    }
}
