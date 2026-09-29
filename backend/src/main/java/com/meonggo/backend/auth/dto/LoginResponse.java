package com.meonggo.backend.auth.dto;

import java.time.Instant;

public record LoginResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        MemberSummary member) {
    public static LoginResponse of(TokenResponse tokens, long memberId, String nickname) {
        return new LoginResponse(
                tokens.tokenType(),
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.accessTokenExpiresAt(),
                tokens.refreshTokenExpiresAt(),
                new MemberSummary(memberId, nickname));
    }

    public record MemberSummary(long memberId, String nickname) {}

    @Override
    public String toString() {
        return "LoginResponse[REDACTED]";
    }
}
