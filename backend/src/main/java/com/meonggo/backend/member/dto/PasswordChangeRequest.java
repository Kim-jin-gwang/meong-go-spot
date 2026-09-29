package com.meonggo.backend.member.dto;

public record PasswordChangeRequest(String currentPassword, String newPassword) {
    @Override
    public String toString() {
        return "PasswordChangeRequest[REDACTED]";
    }
}
