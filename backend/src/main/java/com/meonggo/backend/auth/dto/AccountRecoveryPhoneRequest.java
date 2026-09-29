package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** A9-1. 가입 때 쓴 휴대전화 번호. 응답은 가입 여부와 무관하게 같다. */
public record AccountRecoveryPhoneRequest(@NotBlank String phoneNumber) {
    @Override
    public String toString() {
        return "AccountRecoveryPhoneRequest[redacted]";
    }
}
