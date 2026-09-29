package com.meonggo.backend.post.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.security.PhoneProtection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class PostLocationConfigurationTest {
    @TempDir Path directory;

    @Test
    void loadsPinnedTestCatalogAndPurposeSeparatedKeyring() {
        PostLocationConfiguration configuration = new PostLocationConfiguration(environment());
        assertThat(configuration.regionCodeCatalog().resolve("11680", "1168010100"))
                .isEqualTo("테스트 시군구 테스트동");
        var protection = configuration.locationProtection(phoneProtection());
        assertThat(protection.decrypt(protection.encrypt("역삼역"))).isEqualTo("역삼역");
    }

    @Test
    void failsClosedForMissingFilesSettingsChecksumAndProdClasspath() {
        for (String setting :
                new String[] {
                    "REGION_CODE_DATA_PATH", "REGION_CODE_DATA_VERSION", "REGION_CODE_DATA_SHA256"
                }) {
            MockEnvironment environment = environment().withProperty(setting, "");
            assertThatThrownBy(() -> new PostLocationConfiguration(environment).regionCodeCatalog())
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();
        }
        MockEnvironment missing =
                environment().withProperty("REGION_CODE_DATA_PATH", "/private/not-found.csv");
        assertThatThrownBy(() -> new PostLocationConfiguration(missing).regionCodeCatalog())
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageNotContaining("/private/");
        MockEnvironment prod = environment();
        prod.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> new PostLocationConfiguration(prod).regionCodeCatalog())
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        assertThatThrownBy(
                        () ->
                                new PostLocationConfiguration(prod)
                                        .locationProtection(phoneProtection()))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
        MockEnvironment noKey =
                environment().withProperty("LOCATION_DATA_ENCRYPTION_KEYRING_PATH", "");
        assertThatThrownBy(
                        () ->
                                new PostLocationConfiguration(noKey)
                                        .locationProtection(phoneProtection()))
                .isInstanceOf(IllegalStateException.class)
                .hasNoCause();
    }

    @Test
    void rejectsAllPhoneAndHmacKeyReuseIncludingLoginIdentity() throws Exception {
        for (int reused : new int[] {1, 2, 3, 4, 5, 9}) {
            MockEnvironment environment =
                    withKeyring(
                            "{\"currentKid\":\"current\",\"keys\":{\"current\":\""
                                    + encoded(reused)
                                    + "\"}}");
            assertThatThrownBy(
                            () ->
                                    new PostLocationConfiguration(environment)
                                            .locationProtection(phoneProtection()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause();
        }
    }

    @Test
    void rejectsMalformedOversizedAndDuplicateKeyringWithoutExposingInput() throws Exception {
        for (String json :
                new String[] {
                    "private-input",
                    " ".repeat(8193),
                    "{\"currentKid\":\"current\",\"keys\":{\"current\":\"private-input\"}}",
                    "{\"currentKid\":\"current\",\"keys\":{\"current\":\""
                            + encoded(6)
                            + "\",\"current\":\""
                            + encoded(7)
                            + "\"}}",
                    "{\"currentKid\":\"current\",\"keys\":{\"current\":\""
                            + encoded(6)
                            + "\"},\"unexpected\":true}",
                    "{\"currentKid\":\"current\",\"keys\":{\"current\":\"" + encoded(6) + "\"}} {}"
                }) {
            MockEnvironment environment = withKeyring(json);
            assertThatThrownBy(
                            () ->
                                    new PostLocationConfiguration(environment)
                                            .locationProtection(phoneProtection()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasNoCause()
                    .hasMessageNotContaining("private-input");
        }
    }

    private MockEnvironment withKeyring(String json) throws Exception {
        Path file = directory.resolve("keyring.json");
        Files.writeString(file, json);
        return environment().withProperty("LOCATION_DATA_ENCRYPTION_KEYRING_PATH", file.toString());
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return environment
                .withProperty("REGION_CODE_DATA_PATH", "classpath:location/regions-test.csv")
                .withProperty("REGION_CODE_DATA_VERSION", "test-v1")
                .withProperty(
                        "REGION_CODE_DATA_SHA256",
                        "5db65418e6c6d59e0065f17344bf88340d81fe31e12903b42d5a527675a73ce1")
                .withProperty(
                        "LOCATION_DATA_ENCRYPTION_KEYRING_PATH",
                        "classpath:security/location-test-keyring.json")
                .withProperty("AUTH_LOGIN_ID_HMAC_KEY_V1", encoded(5));
    }

    private static PhoneProtection phoneProtection() {
        return new PhoneProtection(
                "current", Map.of("current", key(1), "previous", key(9)), key(2), key(3), key(4));
    }

    private static String encoded(int value) {
        return Base64.getEncoder().encodeToString(key(value));
    }

    private static byte[] key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }
}
