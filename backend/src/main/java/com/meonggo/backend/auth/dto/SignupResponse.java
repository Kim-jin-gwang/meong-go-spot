package com.meonggo.backend.auth.dto;

import java.time.Instant;

public record SignupResponse(Long memberId, String loginId, String nickname, Instant createdAt) {
    @Override
    public String toString() {
        return "SignupResponse[redacted]";
    }
}
