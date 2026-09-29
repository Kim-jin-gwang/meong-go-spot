package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {
    @Test
    void createsCanonicalOpaqueTokenAndHashesOnlySecret() {
        RefreshToken token = RefreshToken.create();

        assertThat(token.value()).hasSize(69).matches("v1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
        assertThat(token.secretHash()).matches("[0-9a-f]{64}");
        assertThat(RefreshToken.parse(token.value()).matches(token.secretHash())).isTrue();
        assertThat(token.toString())
                .doesNotContain(token.value(), token.selector(), token.secretHash());
    }

    @Test
    void rotationKeepsSelectorAndReplacesSecret() {
        RefreshToken original = RefreshToken.create();
        RefreshToken rotated = RefreshToken.rotate(original.selector());

        assertThat(rotated.selector()).isEqualTo(original.selector());
        assertThat(rotated.secretHash()).isNotEqualTo(original.secretHash());
        assertThat(original.matches(rotated.secretHash())).isFalse();
    }

    @Test
    void rejectsNonCanonicalOrMalformedTokensAsInvalidSession() {
        List<String> invalid =
                List.of(
                        "",
                        "v2.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "v1.AAAAAAAAAAAAAAAAAAAAA=.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "v1.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
                        "v1.AAAAAAAAAAAAAAAAAAAAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        for (String raw : invalid) {
            assertThatThrownBy(() -> RefreshToken.parse(raw))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.INVALID_SESSION);
        }
    }
}
