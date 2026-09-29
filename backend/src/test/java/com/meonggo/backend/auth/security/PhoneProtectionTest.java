package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneProtectionTest {
    private static byte[] key(int value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) value);
        return key;
    }

    private static PhoneProtection protection() {
        return new PhoneProtection(
                "v2", Map.of("v1", key(1), "v2", key(2)), key(3), key(4), key(5));
    }

    @Test
    void normalizesOnlySupportedKoreanMobileFormats() {
        PhoneProtection protection = protection();

        assertThat(protection.normalize("01012345678")).isEqualTo("+821012345678");
        assertThat(protection.normalize("+821012345678")).isEqualTo("+821012345678");
        assertThatThrownBy(() -> protection.normalize("010-1234-5678"))
                .isInstanceOfSatisfying(
                        InputValidationException.class,
                        exception -> assertThat(exception.field()).isEqualTo("phoneNumber"));
    }

    @Test
    void encryptsWithCurrentKeyAndDecryptsCurrentAndPreviousKeys() {
        PhoneProtection protection = protection();
        PhoneProtection oldWriter =
                new PhoneProtection("v1", Map.of("v1", key(1)), key(3), key(4), key(5));

        String current = protection.encrypt("+821012345678");
        String previous = oldWriter.encrypt("+821012345678");

        assertThat(current).startsWith("enc:v1:v2:");
        assertThat(protection.decrypt(current)).isEqualTo("+821012345678");
        assertThat(protection.decrypt(previous)).isEqualTo("+821012345678");
    }

    @Test
    void rejectsTamperedCiphertextWithoutExposingIt() {
        PhoneProtection protection = protection();
        String envelope = protection.encrypt("+821012345678");
        String[] parts = envelope.split(":", -1);
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

        assertThatThrownBy(() -> protection.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(tampered)
                .hasMessageNotContaining("+821012345678");
    }

    @Test
    void rejectsMalformedUnknownAndNonCanonicalEnvelopes() {
        PhoneProtection protection = protection();
        String valid = protection.encrypt("+821012345678");
        String[] parts = valid.split(":", -1);
        String[] invalid = {
            null,
            "enc:v1:v2",
            "enc:v1:v2::",
            valid.replaceFirst("enc", "other"),
            valid.replaceFirst("v1", "v9"),
            valid.replaceFirst(":v2:", ":missing:"),
            "enc:v1:v2:invalid*:invalid*",
            "enc:v1:v2:AA:" + parts[4],
            "enc:v1:v2:" + parts[3] + ":AA",
            valid + "="
        };

        for (String envelope : invalid) {
            assertThatThrownBy(() -> protection.decrypt(envelope))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("+821012345678");
        }
    }

    @Test
    void separatesLookupOtpAndIpHashDomains() {
        PhoneProtection protection = protection();

        assertThat(protection.lookupHash("+821012345678")).matches("[0-9a-f]{64}");
        assertThat(protection.otpHash("verification", "123456"))
                .isNotEqualTo(protection.lookupHash("+821012345678"));
        assertThat(protection.ipHash(new byte[] {127, 0, 0, 1}))
                .isNotEqualTo(protection.ipHash(new byte[] {127, 0, 0, 2}));
    }

    @Test
    void rejectsMissingShortOrReusedKeys() {
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v2", Map.of("v1", key(1)), key(3), key(4), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", new byte[16]), key(3), key(4), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(1)), key(3), key(3), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v2",
                                        Map.of("v1", key(1), "v2", key(1)),
                                        key(3),
                                        key(4),
                                        key(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEveryInvalidKeyCombinationAndDefensivelyCopiesKeys() {
        byte[][] invalidHmacKeys = {null, new byte[31]};
        for (byte[] invalid : invalidHmacKeys) {
            assertThatThrownBy(
                            () ->
                                    new PhoneProtection(
                                            "v1", Map.of("v1", key(1)), invalid, key(4), key(5)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new PhoneProtection(
                                            "v1", Map.of("v1", key(1)), key(3), invalid, key(5)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                            () ->
                                    new PhoneProtection(
                                            "v1", Map.of("v1", key(1)), key(3), key(4), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(1)), key(3), key(4), key(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(1)), key(3), key(4), key(4)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(3)), key(3), key(4), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(4)), key(3), key(4), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1", Map.of("v1", key(5)), key(3), key(4), key(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PhoneProtection(
                                        "v1",
                                        Map.of("v1", key(1), "v2", key(2), "v3", key(6)),
                                        key(3),
                                        key(4),
                                        key(5)))
                .isInstanceOf(IllegalArgumentException.class);

        byte[] encryptionKey = key(1);
        byte[] lookupKey = key(3);
        PhoneProtection protection =
                new PhoneProtection("v1", Map.of("v1", encryptionKey), lookupKey, key(4), key(5));
        String encryptedBeforeMutation = protection.encrypt("+821012345678");
        String hashBeforeMutation = protection.lookupHash("+821012345678");
        encryptionKey[0] ^= 1;
        lookupKey[0] ^= 1;

        assertThat(protection.decrypt(encryptedBeforeMutation)).isEqualTo("+821012345678");
        assertThat(protection.lookupHash("+821012345678")).isEqualTo(hashBeforeMutation);
    }
}
