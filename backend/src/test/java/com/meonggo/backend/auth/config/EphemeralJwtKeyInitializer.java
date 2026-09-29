package com.meonggo.backend.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

public final class EphemeralJwtKeyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!context.getEnvironment().matchesProfiles("test")) {
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path directory = Files.createTempDirectory("auth-jwt-test-");
            Path privateKey = directory.resolve("private.pem");
            Path jwks = directory.resolve("public.jwks");
            String encoded =
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(pair.getPrivate().getEncoded());
            Files.writeString(
                    privateKey,
                    "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n",
                    StandardCharsets.US_ASCII);
            RSAKey publicJwk =
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                            .keyID("test-active")
                            .build();
            Files.writeString(jwks, new JWKSet(publicJwk.toPublicJWK()).toString());
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "ephemeralJwtKeys",
                                    Map.ofEntries(
                                            Map.entry("AUTH_ACCESS_TOKEN_TTL", "PT15M"),
                                            Map.entry("AUTH_REFRESH_TOKEN_TTL", "P30D"),
                                            Map.entry("AUTH_JWT_CLOCK_SKEW", "PT30S"),
                                            Map.entry("AUTH_JWT_ISSUER", "meonggocuisine-auth"),
                                            Map.entry("AUTH_JWT_AUDIENCE", "meonggocuisine-api"),
                                            Map.entry(
                                                    "AUTH_JWT_CLIENT_ID", "meonggocuisine-android"),
                                            Map.entry("AUTH_JWT_ACTIVE_KID", "test-active"),
                                            Map.entry(
                                                    "AUTH_JWT_PRIVATE_KEY_PATH",
                                                    privateKey.toString()),
                                            Map.entry(
                                                    "AUTH_JWT_PUBLIC_JWKS_PATH",
                                                    jwks.toString()))));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create ephemeral test JWT keys", ex);
        }
    }
}
