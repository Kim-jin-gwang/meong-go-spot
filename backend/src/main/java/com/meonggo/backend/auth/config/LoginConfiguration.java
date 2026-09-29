package com.meonggo.backend.auth.config;

import com.meonggo.backend.auth.security.LoginIdentityProtection;
import com.meonggo.backend.auth.security.PhoneProtection;
import java.util.Base64;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class LoginConfiguration {
    private final Environment environment;

    public LoginConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    LoginIdentityProtection loginIdentityProtection(PhoneProtection phoneProtection) {
        byte[] accountKey = key("AUTH_LOGIN_ID_HMAC_KEY_V1");
        byte[] ipKey = key("AUTH_IP_HMAC_KEY_V1");
        phoneProtection.requireDistinctKeys(accountKey);
        return new LoginIdentityProtection(accountKey, ipKey);
    }

    private byte[] key(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required authentication setting is missing: " + name);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid authentication key encoding");
        }
    }
}
