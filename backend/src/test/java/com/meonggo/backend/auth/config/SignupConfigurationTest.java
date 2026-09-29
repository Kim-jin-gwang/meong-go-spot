package com.meonggo.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class SignupConfigurationTest {
    @Test
    void missingKeyringAndMalformedKeyFailWithoutLeakingValues() {
        MockEnvironment environment = environment();
        SignupConfiguration configuration = new SignupConfiguration(environment);
        assertThatThrownBy(() -> configuration.phoneProtection(JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class);
        environment
                .withProperty(
                        "PHONE_DATA_ENCRYPTION_KEYRING_PATH",
                        "classpath:security/phone-test-keyring.json")
                .withProperty("PHONE_LOOKUP_HMAC_KEY_V1", "invalid-private-key-value");
        assertThatThrownBy(() -> configuration.phoneProtection(JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageNotContaining("invalid-private-key-value");
    }

    @Test
    void fakeSmsCannotStartWithProdEvenIfDevAlsoActive() {
        MockEnvironment environment = environment().withProperty("SMS_PROVIDER", "FAKE");
        environment.setActiveProfiles("prod", "dev", "test");
        SignupConfiguration configuration = new SignupConfiguration(environment);
        assertThatThrownBy(() -> configuration.smsSender(JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(configuration::otpGenerator).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(configuration::testPhoneSignupBypass)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testPhoneBypassRequiresFakeSmsAndMatchesOnlyTheMasterNumber() {
        SignupConfiguration fakeConfiguration =
                new SignupConfiguration(environment().withProperty("SMS_PROVIDER", "FAKE"));
        var enabled = fakeConfiguration.testPhoneSignupBypass();
        assertThat(enabled.normalizedPhone("011-0000-0000")).isEqualTo("+821100000000");
        assertThat(enabled.normalizedPhone("01100000000")).isEqualTo("+821100000000");
        assertThat(enabled.normalizedPhone("01000000000")).isNull();

        SignupConfiguration solapiConfiguration = new SignupConfiguration(environment());
        assertThat(solapiConfiguration.testPhoneSignupBypass().normalizedPhone("011-0000-0000"))
                .isNull();
    }

    @Test
    void testOtpUsesRandomGeneratorWhileDevUsesConfiguredCode() {
        MockEnvironment environment =
                environment()
                        .withProperty("SMS_PROVIDER", "FAKE")
                        .withProperty("SMS_FAKE_FIXED_OTP", "012345");
        SignupConfiguration configuration = new SignupConfiguration(environment);
        var testGenerator = configuration.otpGenerator();
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            codes.add(testGenerator.generate());
        }
        assertThat(codes).hasSizeGreaterThan(1).allMatch(code -> code.matches("[0-9]{6}"));
        environment.setActiveProfiles("dev");
        assertThat(configuration.otpGenerator().generate()).isEqualTo("012345");
    }

    @Test
    void concurrencyCannotBeRaisedPastPolicy() {
        SignupConfiguration configuration =
                new SignupConfiguration(
                        environment().withProperty("AUTH_ARGON2_MAX_CONCURRENCY", "5"));
        assertThatThrownBy(configuration::passwordWork).isInstanceOf(IllegalStateException.class);
    }

    private MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment;
    }
}
