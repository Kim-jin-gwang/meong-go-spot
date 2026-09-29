package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhoneProofTest {
    @Test
    void generatedProofHasExactFormatAndRoundTripsWithoutStoringSecret() {
        PhoneProof proof = PhoneProof.generate();
        assertThat(proof.token())
                .hasSize(70)
                .matches("pv1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}");
        PhoneProof parsed = PhoneProof.parse(proof.token());
        assertThat(parsed.secretHash()).hasSize(64).isEqualTo(proof.secretHash());
        assertThat(parsed.selector()).isEqualTo(proof.selector());
        assertThat(parsed.toString()).doesNotContain(proof.token(), proof.secretHash());
    }

    @Test
    void rejectsVersionsPaddingAndNonCanonicalBase64() {
        String token = "pv1." + "A".repeat(22) + "." + "A".repeat(43);
        for (String invalid :
                List.of(
                        token.replace("pv1", "pv2"),
                        token + "=",
                        token.substring(0, 69),
                        token.substring(0, 69) + "B")) {
            assertThatThrownBy(() -> PhoneProof.parse(invalid))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageNotContaining(invalid);
        }
    }
}
