package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import org.junit.jupiter.api.Test;

class LoginPasswordPolicyTest {
    private final SignupInputPolicy policy = new SignupInputPolicy();

    @Test
    void presentInvalidPasswordsBecomeDummyCandidatesWithoutFieldErrors() {
        for (String raw :
                new String[] {
                    "",
                    "short",
                    "a".repeat(7),
                    "a".repeat(65),
                    "a".repeat(8) + " ",
                    "a".repeat(8) + "\u200B"
                }) {
            assertThat(policy.normalizeLoginPassword(raw)).isNull();
        }
    }

    @Test
    void rawLimitAndMissingPasswordFailBeforeNormalization() {
        assertThatThrownBy(() -> policy.normalizeLoginPassword(null))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> policy.normalizeLoginPassword("a".repeat(257)))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void loginNormalizesNfcWithoutReapplyingSignupBlocklistOrIdentityRules() {
        assertThat(policy.normalizeLoginPassword("x".repeat(7) + "e\u0301"))
                .isEqualTo("x".repeat(7) + "é");
        assertThat(policy.normalizeLoginPassword("12345678901234567890"))
                .isEqualTo("12345678901234567890");
        assertThat(policy.normalizeLoginPassword("a".repeat(8))).isEqualTo("a".repeat(8));
    }
}
