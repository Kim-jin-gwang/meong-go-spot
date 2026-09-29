package com.meonggo.backend.post.config;

import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.post.location.LocationProtection;
import com.meonggo.backend.post.location.RegionCodeCatalog;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fail closed before exposing a location writer when reference data or protection is unavailable.
 */
@Configuration
public class PostLocationConfiguration {
    private static final int MAX_KEYRING_BYTES = 8192;
    private final Environment environment;

    public PostLocationConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    RegionCodeCatalog regionCodeCatalog() {
        try {
            return new RegionCodeCatalog(
                    readBounded(
                            required("REGION_CODE_DATA_PATH"), RegionCodeCatalog.MAX_DATA_BYTES),
                    required("REGION_CODE_DATA_VERSION"),
                    required("REGION_CODE_DATA_SHA256"));
        } catch (Exception exception) {
            throw new IllegalStateException("지역 기준 데이터 설정을 확인할 수 없습니다.");
        }
    }

    @Bean
    LocationProtection locationProtection(PhoneProtection phoneProtection) {
        try {
            byte[] content =
                    readBounded(
                            required("LOCATION_DATA_ENCRYPTION_KEYRING_PATH"), MAX_KEYRING_BYTES);
            JsonMapper mapper =
                    JsonMapper.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .build();
            JsonNode root = mapper.readTree(content);
            if (root == null
                    || !root.isObject()
                    || root.size() != 2
                    || !root.path("currentKid").isString()
                    || !root.path("keys").isObject()
                    || root.path("keys").size() < 1
                    || root.path("keys").size() > 2) {
                throw new IllegalArgumentException();
            }
            byte[] loginKey = decodeKey(required("AUTH_LOGIN_ID_HMAC_KEY_V1"));
            if (loginKey.length < 32) {
                throw new IllegalArgumentException();
            }
            Map<String, byte[]> keys = new HashMap<>();
            JsonNode entries = root.path("keys");
            for (String kid : entries.propertyNames()) {
                if (!entries.path(kid).isString()) {
                    throw new IllegalArgumentException();
                }
                byte[] key = decodeKey(entries.path(kid).asString());
                phoneProtection.requireDistinctKeys(key);
                if (Arrays.equals(loginKey, key)) {
                    throw new IllegalArgumentException();
                }
                keys.put(kid, key);
            }
            return new LocationProtection(root.path("currentKid").asString(), keys);
        } catch (Exception exception) {
            throw new IllegalStateException("위치 암호화 키 설정을 확인할 수 없습니다.");
        }
    }

    private byte[] readBounded(String location, int maximumBytes) throws java.io.IOException {
        try (InputStream input = open(location)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length == 0 || bytes.length > maximumBytes) {
                throw new IllegalArgumentException();
            }
            return bytes;
        }
    }

    private InputStream open(String location) throws java.io.IOException {
        if (location.startsWith("classpath:")) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalArgumentException();
            }
            return new ClassPathResource(location.substring("classpath:".length()))
                    .getInputStream();
        }
        return Files.newInputStream(Path.of(location));
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static byte[] decodeKey(String value) {
        if (value.length() > MAX_KEYRING_BYTES) {
            throw new IllegalArgumentException();
        }
        byte[] decoded = Base64.getDecoder().decode(value);
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException();
        }
        return decoded;
    }
}
