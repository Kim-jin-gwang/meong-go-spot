package com.meonggo.backend.auth.security;

public record AuthPrincipal(long memberId, long sessionId) {
    public AuthPrincipal {
        if (memberId <= 0 || sessionId <= 0) {
            throw new IllegalArgumentException("Authentication identifiers must be positive");
        }
    }
}
