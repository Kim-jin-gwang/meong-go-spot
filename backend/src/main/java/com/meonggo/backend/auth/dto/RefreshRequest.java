package com.meonggo.backend.auth.dto;

public record RefreshRequest(String refreshToken) {
    @Override
    public String toString() {
        return "RefreshRequest[REDACTED]";
    }
}
