package com.meonggo.backend.auth.dto;

import java.time.Instant;

public record TokenResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt) {
    @Override
    public String toString() {
        return "TokenResponse[REDACTED]";
    }
}
