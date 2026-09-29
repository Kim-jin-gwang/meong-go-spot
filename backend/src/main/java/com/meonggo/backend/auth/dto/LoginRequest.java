package com.meonggo.backend.auth.dto;

public record LoginRequest(String loginId, String password) {
    @Override
    public String toString() {
        return "LoginRequest[REDACTED]";
    }
}
