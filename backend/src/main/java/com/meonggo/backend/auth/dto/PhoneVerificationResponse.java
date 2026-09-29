package com.meonggo.backend.auth.dto;

import java.time.Instant;

public record PhoneVerificationResponse(String phoneVerificationToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "PhoneVerificationResponse[redacted]";
    }
}
