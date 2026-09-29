package com.meonggo.backend.auth.dto;

import java.time.Instant;

/** A9-2 응답 — 번호 소유를 방금 증명했으므로 아이디는 가리지 않고 돌려준다. 복구 증명은 10분·1회용. */
public record AccountRecoveryConfirmationResponse(
        String loginId, String recoveryToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "AccountRecoveryConfirmationResponse[redacted]";
    }
}
