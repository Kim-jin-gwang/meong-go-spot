package com.meonggo.backend.auth.config;

import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.auth.security.TestPhoneSignupBypass;
import com.meonggo.backend.auth.service.PhoneVerificationService.OtpGenerator;
import com.meonggo.backend.auth.sms.FakeSmsSender;
import com.meonggo.backend.auth.sms.SmsSender;
import com.meonggo.backend.auth.sms.SolapiSmsSender;
import com.meonggo.backend.auth.web.AuthRequestBodyFilter;
import com.meonggo.backend.auth.web.ClientIpResolver;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SignupConfiguration {
    private final Environment environment;

    public SignupConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    PhoneProtection phoneProtection(ObjectMapper mapper) {
        String kid;
        Map<String, byte[]> keys = new HashMap<>();
        String location = required("PHONE_DATA_ENCRYPTION_KEYRING_PATH");
        try (InputStream input = keyringInput(location)) {
            JsonNode root = mapper.readTree(input);
            kid = root.path("currentKid").asString();
            JsonNode entries = root.path("keys");
            for (String name : entries.propertyNames()) {
                keys.put(name, Base64.getDecoder().decode(entries.path(name).asString()));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load phone encryption keyring");
        }
        return new PhoneProtection(
                kid,
                keys,
                key("PHONE_LOOKUP_HMAC_KEY_V1"),
                key("PHONE_OTP_HMAC_KEY_V1"),
                key("AUTH_IP_HMAC_KEY_V1"));
    }

    @Bean
    SignupInputPolicy signupInputPolicy() {
        return new SignupInputPolicy();
    }

    @Bean
    TestPhoneSignupBypass testPhoneSignupBypass() {
        return new TestPhoneSignupBypass(fakeEnabled());
    }

    @Bean
    PasswordWork passwordWork() {
        try {
            return new PasswordWork(
                    Integer.parseInt(environment.getProperty("AUTH_ARGON2_MAX_CONCURRENCY", "4")));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid Argon2 concurrency configuration");
        }
    }

    @Bean
    ClientIpResolver clientIpResolver() {
        String configured = environment.getProperty("AUTH_TRUSTED_PROXY_CIDRS", "");
        List<String> cidrs =
                configured.isBlank()
                        ? List.of()
                        : Arrays.stream(configured.split(",")).map(String::strip).toList();
        return new ClientIpResolver(cidrs);
    }

    @Bean
    FilterRegistrationBean<AuthRequestBodyFilter> authRequestBodyFilter(ObjectMapper mapper) {
        FilterRegistrationBean<AuthRequestBodyFilter> registration =
                new FilterRegistrationBean<>(new AuthRequestBodyFilter(mapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    SmsSender smsSender(ObjectMapper mapper) {
        if (fakeEnabled()) {
            return new FakeSmsSender();
        }
        Properties credentials = new Properties();
        try (InputStream input =
                Files.newInputStream(Path.of(required("SOLAPI_CREDENTIALS_PATH")))) {
            credentials.load(input);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load SOLAPI credentials");
        }
        for (String name : List.of("apiKey", "apiSecret", "senderNumber")) {
            if (credentials.getProperty(name, "").isBlank()) {
                throw new IllegalStateException("Incomplete SOLAPI credentials");
            }
        }
        if (!credentials.getProperty("senderNumber").matches("[0-9]{8,15}")) {
            throw new IllegalStateException("Invalid SOLAPI sender configuration");
        }
        return new SolapiSmsSender(
                mapper,
                URI.create("https://api.solapi.com/messages/v4/send-many/detail"),
                credentials.getProperty("apiKey"),
                credentials.getProperty("apiSecret"),
                credentials.getProperty("senderNumber"));
    }

    @Bean
    OtpGenerator otpGenerator() {
        if (!fakeEnabled() || environment.acceptsProfiles(Profiles.of("test"))) {
            return OtpGenerator.random();
        }
        String fixedCode = required("SMS_FAKE_FIXED_OTP");
        if (!fixedCode.matches("[0-9]{6}")) {
            throw new IllegalStateException("Fake SMS code must have six digits");
        }
        return () -> fixedCode;
    }

    private boolean fakeEnabled() {
        String provider = environment.getProperty("SMS_PROVIDER", "SOLAPI");
        if ("SOLAPI".equals(provider)) {
            return false;
        }
        if (!"FAKE".equals(provider)
                || environment.acceptsProfiles(Profiles.of("prod"))
                || !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException("Fake SMS requires dev/test profile without prod");
        }
        return true;
    }

    private InputStream keyringInput(String location) throws java.io.IOException {
        if (location.startsWith("classpath:")
                && environment.acceptsProfiles(Profiles.of("test"))
                && !environment.acceptsProfiles(Profiles.of("prod"))) {
            return new ClassPathResource(location.substring("classpath:".length()))
                    .getInputStream();
        }
        return Files.newInputStream(Path.of(location));
    }

    private byte[] key(String name) {
        try {
            return Base64.getDecoder().decode(required(name));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid authentication key encoding");
        }
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required authentication setting is missing: " + name);
        }
        return value;
    }
}
