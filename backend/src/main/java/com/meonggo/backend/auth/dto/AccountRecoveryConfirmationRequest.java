package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** A9-2. 인증 코드 확인 — 가입(A0-2)과 달리 개인정보 동의는 다시 받지 않는다(이미 회원이다). */
public record AccountRecoveryConfirmationRequest(
        @NotBlank String phoneNumber,
        @NotBlank @Pattern(regexp = "[0-9]{6}", message = "인증 코드는 숫자 6자리여야 합니다.")
                String verificationCode) {
    @Override
    public String toString() {
        return "AccountRecoveryConfirmationRequest[redacted]";
    }
}
