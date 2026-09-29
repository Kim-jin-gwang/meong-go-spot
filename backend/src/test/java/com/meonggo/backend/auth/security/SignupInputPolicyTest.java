package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class SignupInputPolicyTest {
    private static SignupInputPolicy policy(String entries) throws Exception {
        byte[] bytes = entries.getBytes(StandardCharsets.UTF_8);
        String checksum =
                java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        int lines =
                entries.isEmpty()
                        ? 0
                        : entries.split("\n", -1).length - (entries.endsWith("\n") ? 1 : 0);
        return new SignupInputPolicy(
                new ByteArrayInputStream(bytes), bytes.length, lines, checksum);
    }

    @Test
    void loadsThePinnedClasspathBlocklistOnlyWhenAllMetadataMatches() {
        assertThatCode(SignupInputPolicy::new).doesNotThrowAnyException();
    }

    @Test
    void canonicalizesLoginIdWithLocaleIndependentLowercaseAndNfc() throws Exception {
        SignupInputPolicy policy = policy("blocked-password\n");

        assertThat(policy.canonicalLoginId("E\u0301XAMPLE")).isEqualTo("éxample");
    }

    @Test
    void rejectsLoginIdWhitespaceInsteadOfTrimmingIt() throws Exception {
        SignupInputPolicy policy = policy("blocked-password\n");

        assertThatThrownBy(() -> policy.canonicalLoginId(" mango"))
                .isInstanceOf(InputValidationException.class)
                .extracting("field")
                .isEqualTo("loginId");
    }

    @Test
    void normalizesNicknameAndPreservesInternalSpaces() throws Exception {
        SignupInputPolicy policy = policy("blocked-password\n");

        assertThat(policy.normalizeNickname("  망고  보호자  ")).isEqualTo("망고  보호자");
    }

    @Test
    void rejectsPasswordThatIsBlockedLoginIdOrContainsForbiddenCharacters() throws Exception {
        SignupInputPolicy policy = policy("known-bad-password\n");

        assertThatThrownBy(() -> policy.normalizePassword("Known-Bad-Password", "somebody"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> policy.normalizePassword("same-login-name", "same-login-name"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> policy.normalizePassword("validbut has space", "somebody"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void acceptsEightCodePointsAndRejectsSevenAndSixtyFive() throws Exception {
        SignupInputPolicy policy = policy("known-bad-password\n");

        assertThat(policy.normalizePassword("1234567!", "somebody")).isEqualTo("1234567!");
        assertThatThrownBy(() -> policy.normalizePassword("123456!", "somebody"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> policy.normalizePassword("a".repeat(65), "somebody"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void preservesNfcPasswordAndEnforcesRawCodePointLimit() throws Exception {
        SignupInputPolicy policy = policy("known-bad-password\n");
        String decomposed = "e\u0301" + "a".repeat(7);

        assertThat(policy.normalizePassword(decomposed, "somebody")).isEqualTo("é" + "a".repeat(7));
        assertThatThrownBy(() -> policy.normalizePassword("a".repeat(257), "somebody"))
                .isInstanceOf(InputValidationException.class);
        assertThatThrownBy(() -> policy.canonicalLoginId("a".repeat(257)))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void rejectsEveryForbiddenPasswordUnicodeCategory() throws Exception {
        SignupInputPolicy policy = policy("known-bad-password\n");
        String[] forbidden = {"\u00a0", "\u0001", "\u200b", "\ud800", "\ue000", "\u0378"};

        for (String character : forbidden) {
            assertThatThrownBy(
                            () ->
                                    policy.normalizePassword(
                                            "safe-password-2026" + character, "somebody"))
                    .isInstanceOf(InputValidationException.class);
        }
    }

    @Test
    void comparesBlockedValuesAsCaseInsensitiveNfcWholeValues() throws Exception {
        SignupInputPolicy policy = policy("ÉXAMPLE-PASSWORD-2026\n");

        assertThatThrownBy(
                        () -> policy.normalizePassword("e\u0301xample-password-2026", "somebody"))
                .isInstanceOf(InputValidationException.class);
        assertThat(policy.normalizePassword("prefix-éxample-password-2026", "somebody"))
                .isEqualTo("prefix-éxample-password-2026");
        assertThatThrownBy(() -> policy.normalizePassword("멍고반점", "somebody"))
                .isInstanceOf(InputValidationException.class);
    }

    @Test
    void failsClosedWhenBlocklistMetadataDoesNotMatch() {
        byte[] bytes = "entry\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(
                        () ->
                                new SignupInputPolicy(
                                        new ByteArrayInputStream(bytes),
                                        bytes.length,
                                        1,
                                        "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }
}
