package com.meonggo.backend.post.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LocationProtectionTest {
    @Test
    void roundTripUsesRandomNonceAndCanonicalEnvelope() {
        LocationProtection protection =
                new LocationProtection("current", Map.of("current", key(6)));
        String first = protection.encrypt("역삼역");
        String second = protection.encrypt("역삼역");
        assertThat(first).startsWith("enc:v1:current:").doesNotContain("=").isNotEqualTo(second);
        assertThat(first.split(":")[3]).hasSize(16).isNotEqualTo(second.split(":")[3]);
        assertThat(protection.decrypt(first)).isEqualTo("역삼역");
    }

    @Test
    void rejectsTamperingWrongKeysWrongPurposeAndMalformedEnvelopesSafely() throws Exception {
        LocationProtection protection =
                new LocationProtection("current", Map.of("current", key(6)));
        String encrypted = protection.encrypt("역삼역");
        String[] parts = encrypted.split(":");
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[4]);
        ciphertext[0] ^= 1;
        String tampered =
                String.join(
                        ":",
                        parts[0],
                        parts[1],
                        parts[2],
                        parts[3],
                        Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        byte[] nonce = Base64.getUrlDecoder().decode(parts[3]);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key(6), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD("mgbj:member-phone:v1".getBytes(StandardCharsets.UTF_8));
        String phonePurpose =
                "enc:v1:current:"
                        + parts[3]
                        + ":"
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        cipher.doFinal("역삼역".getBytes(StandardCharsets.UTF_8)));
        for (String invalid :
                new String[] {
                    tampered,
                    phonePurpose,
                    encrypted + "=",
                    encrypted.replace(":v1:", ":v2:"),
                    encrypted.replace(":current:", ":unknown:"),
                    "private-input",
                    "enc:v1:current:AA:AA"
                }) {
            assertThatThrownBy(() -> protection.decrypt(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("역삼역")
                    .hasMessageNotContaining(invalid);
        }
        LocationProtection wrong = new LocationProtection("current", Map.of("current", key(7)));
        assertThatThrownBy(() -> wrong.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    @Test
    void rotationReadsPreviousWritesCurrentAndDefensivelyCopiesKeys() {
        byte[] previous = key(6);
        LocationProtection old = new LocationProtection("old", Map.of("old", previous));
        String oldValue = old.encrypt("역삼역");
        LocationProtection rotated =
                new LocationProtection("new", Map.of("old", previous, "new", key(7)));
        Arrays.fill(previous, (byte) 0);
        assertThat(rotated.decrypt(oldValue)).isEqualTo("역삼역");
        assertThat(rotated.encrypt("역삼역")).startsWith("enc:v1:new:");
        assertThat(rotated.toString())
                .doesNotContain("old", "new", Base64.getEncoder().encodeToString(key(7)));
    }

    @Test
    void rejectsMissingMalformedRepeatedAndExcessiveKeys() {
        for (Map<String, byte[]> keys :
                java.util.List.of(
                        Map.<String, byte[]>of(),
                        Map.of("current", new byte[31]),
                        Map.of("current", key(6), "old", key(6)),
                        Map.of("current", key(6), "old", key(7), "older", key(8)),
                        Map.of("invalid:kid", key(6)))) {
            assertThatThrownBy(() -> new LocationProtection("current", keys))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasNoCause();
        }
    }

    private static byte[] key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }
}
