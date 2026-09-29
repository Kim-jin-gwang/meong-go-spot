package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** A9-3. 아이디 + 복구 증명 + 새 비밀번호. 증명이 그 아이디의 번호에 묶여 있어야 한다. */
public record PasswordResetRequest(
        @NotBlank String loginId, @NotBlank String recoveryToken, @NotBlank String newPassword) {
    @Override
    public String toString() {
        return "PasswordResetRequest[redacted]";
    }
}
