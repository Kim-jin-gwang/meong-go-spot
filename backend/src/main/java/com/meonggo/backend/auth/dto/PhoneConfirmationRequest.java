package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PhoneConfirmationRequest(
        @NotBlank String phoneNumber,
        @NotBlank @Pattern(regexp = "[0-9]{6}", message = "인증 코드는 숫자 6자리여야 합니다.")
                String verificationCode,
        @NotNull @AssertTrue Boolean privacyCollectionAgreed,
        String privacyCollectionPolicyVersion) {
    @Override
    public String toString() {
        return "PhoneConfirmationRequest[redacted]";
    }
}
