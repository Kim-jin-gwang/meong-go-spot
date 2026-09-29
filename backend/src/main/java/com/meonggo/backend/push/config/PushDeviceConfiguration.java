package com.meonggo.backend.push.config;

import com.meonggo.backend.push.security.PushTokenProtection;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class PushDeviceConfiguration {
    @Bean
    PushTokenProtection pushTokenProtection(Environment environment, ObjectMapper mapper) {
        String location = required(environment, "FCM_TOKEN_ENCRYPTION_KEYRING_PATH");
        try (InputStream input = open(environment, location)) {
            JsonNode root = mapper.readTree(input);
            String currentKeyId = root.path("currentKid").asString();
            JsonNode entries = root.path("keys");
            Map<String, byte[]> encryptionKeys = new HashMap<>();
            for (String keyId : entries.propertyNames()) {
                encryptionKeys.put(
                        keyId, Base64.getDecoder().decode(entries.path(keyId).asString()));
            }
            byte[] lookup =
                    Base64.getDecoder()
                            .decode(required(environment, "FCM_TOKEN_LOOKUP_HMAC_KEY_V1"));
            return new PushTokenProtection(currentKeyId, encryptionKeys, lookup);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid FCM token protection configuration");
        }
    }

    private static InputStream open(Environment environment, String location)
            throws java.io.IOException {
        if (location.startsWith("classpath:")) {
            if (environment.acceptsProfiles(Profiles.of("prod")))
                throw new IllegalArgumentException();
            return new ClassPathResource(location.substring("classpath:".length()))
                    .getInputStream();
        }
        return Files.newInputStream(Path.of(location));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
}
