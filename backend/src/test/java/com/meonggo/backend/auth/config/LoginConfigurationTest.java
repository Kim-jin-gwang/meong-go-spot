package com.meonggo.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.security.PhoneProtection;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LoginConfigurationTest {
    @Test
    void accountKeyMustDifferFromSharedIpAndEveryPhoneProtectionKey() {
        byte[] encryption = key(1);
        byte[] lookup = key(2);
        byte[] otp = key(3);
        byte[] ip = key(4);
        PhoneProtection phone =
                new PhoneProtection("v1", Map.of("v1", encryption), lookup, otp, ip);

        for (byte[] reused : new byte[][] {encryption, lookup, otp, ip}) {
            LoginConfiguration configuration = configuration(reused, ip);
            assertThatThrownBy(() -> configuration.loginIdentityProtection(phone))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining(Base64.getEncoder().encodeToString(reused));
        }
    }

    @Test
    void independentAccountKeyCreatesProtection() {
        byte[] ip = key(4);
        PhoneProtection phone = new PhoneProtection("v1", Map.of("v1", key(1)), key(2), key(3), ip);

        configuration(key(5), ip).loginIdentityProtection(phone);
    }

    private static LoginConfiguration configuration(byte[] accountKey, byte[] ipKey) {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty(
                                "AUTH_LOGIN_ID_HMAC_KEY_V1",
                                Base64.getEncoder().encodeToString(accountKey))
                        .withProperty(
                                "AUTH_IP_HMAC_KEY_V1", Base64.getEncoder().encodeToString(ipKey));
        return new LoginConfiguration(environment);
    }

    private static byte[] key(int value) {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) value);
        return key;
    }
}
