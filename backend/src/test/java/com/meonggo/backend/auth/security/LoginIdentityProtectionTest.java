package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LoginIdentityProtectionTest {
    @Test
    void identityHashesAreDeterministicPurposeSeparatedAndIndependentOfCallerKeyMutation() {
        byte[] account = new byte[32];
        byte[] ip = new byte[32];
        Arrays.fill(ip, (byte) 1);
        var protection = new LoginIdentityProtection(account, ip);
        String hash = protection.accountHash("owner");
        assertThat(hash).matches("[a-f0-9]{64}").doesNotContain("owner");
        assertThat(protection.ipHash(new byte[] {127, 0, 0, 1})).isNotEqualTo(hash);
        account[0] = 7;
        assertThat(protection.accountHash("owner")).isEqualTo(hash);
    }

    @Test
    void shortOrReusedKeysAreRejected() {
        assertThatThrownBy(() -> new LoginIdentityProtection(new byte[31], new byte[32]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginIdentityProtection(new byte[32], new byte[32]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
