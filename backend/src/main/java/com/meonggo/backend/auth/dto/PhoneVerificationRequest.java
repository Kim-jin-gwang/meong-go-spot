package com.meonggo.backend.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PhoneVerificationRequest(
        @NotBlank String phoneNumber,
        @NotNull @AssertTrue Boolean privacyCollectionAgreed,
        String privacyCollectionPolicyVersion) {
    @Override
    public String toString() {
        return "PhoneVerificationRequest[redacted]";
    }
}
