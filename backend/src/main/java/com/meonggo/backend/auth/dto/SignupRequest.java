package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
        @NotBlank String loginId,
        @NotBlank String password,
        @NotBlank String nickname,
        @NotBlank String phoneNumber,
        String phoneVerificationToken,
        @NotNull @AssertTrue Boolean privacyCollectionAgreed,
        String privacyCollectionPolicyVersion) {
    @Override
    public String toString() {
        return "SignupRequest[redacted]";
    }
}
