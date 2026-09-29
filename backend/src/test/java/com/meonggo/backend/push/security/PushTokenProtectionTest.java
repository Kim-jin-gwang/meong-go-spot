package com.meonggo.backend.push.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PushTokenProtectionTest {
    private static final byte[] OLD_KEY = new byte[32];
    private static final byte[] NEW_KEY = new byte[32];
    private static final byte[] LOOKUP_KEY = new byte[32];

    static {
        java.util.Arrays.fill(OLD_KEY, (byte) 1);
        java.util.Arrays.fill(NEW_KEY, (byte) 2);
        java.util.Arrays.fill(LOOKUP_KEY, (byte) 3);
    }

    @Test
    void decryptsPreviousKeyDuringRotationAndWritesCurrentKey() {
        var oldProtection = new PushTokenProtection("old", Map.of("old", OLD_KEY), LOOKUP_KEY);
        String oldCiphertext = oldProtection.protect("registration-token").ciphertext();
        var rotated =
                new PushTokenProtection("new", Map.of("old", OLD_KEY, "new", NEW_KEY), LOOKUP_KEY);

        assertThat(rotated.decrypt(oldCiphertext)).isEqualTo("registration-token");
        assertThat(rotated.protect("new-token").ciphertext()).startsWith("enc:v1:new:");
    }
}
