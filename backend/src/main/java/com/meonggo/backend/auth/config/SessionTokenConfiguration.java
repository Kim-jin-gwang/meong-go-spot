package com.meonggo.backend.auth.config;

import com.meonggo.backend.auth.security.JwtTokenService;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class SessionTokenConfiguration {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final String ISSUER = "meonggocuisine-auth";
    private static final String AUDIENCE = "meonggocuisine-api";
    private static final String CLIENT_ID = "meonggocuisine-android";

    private final Environment environment;

    public SessionTokenConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean("authClock")
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtTokenService jwtTokenService(@Qualifier("authClock") Clock clock) {
        String activeKid = required("AUTH_JWT_ACTIVE_KID");
        Duration accessTtl = requiredDuration("AUTH_ACCESS_TOKEN_TTL", ACCESS_TTL);
        Duration refreshTtl = requiredDuration("AUTH_REFRESH_TOKEN_TTL", REFRESH_TTL);
        Duration clockSkew = requiredDuration("AUTH_JWT_CLOCK_SKEW", CLOCK_SKEW);
        String issuer = requiredIdentifier("AUTH_JWT_ISSUER", ISSUER);
        String audience = requiredIdentifier("AUTH_JWT_AUDIENCE", AUDIENCE);
        String clientId = requiredIdentifier("AUTH_JWT_CLIENT_ID", CLIENT_ID);
        RSAPrivateKey privateKey = loadPrivateKey(required("AUTH_JWT_PRIVATE_KEY_PATH"));
        Map<String, RSAPublicKey> publicKeys =
                loadPublicKeys(required("AUTH_JWT_PUBLIC_JWKS_PATH"));
        RSAPublicKey activePublicKey = publicKeys.get(activeKid);
        if (!(privateKey instanceof RSAPrivateCrtKey privateCrtKey)
                || activePublicKey == null
                || !activePublicKey.getModulus().equals(privateKey.getModulus())
                || !activePublicKey.getPublicExponent().equals(privateCrtKey.getPublicExponent())) {
            throw new IllegalStateException("Active JWT key pair does not match");
        }
        return new JwtTokenService(
                activeKid,
                privateKey,
                publicKeys,
                accessTtl,
                refreshTtl,
                clockSkew,
                issuer,
                audience,
                clientId,
                clock);
    }

    private RSAPrivateKey loadPrivateKey(String location) {
        try {
            String pem = Files.readString(Path.of(location), StandardCharsets.US_ASCII);
            String prefix = "-----BEGIN PRIVATE KEY-----";
            String suffix = "-----END PRIVATE KEY-----";
            if (!pem.contains(prefix) || !pem.contains(suffix)) {
                throw new IllegalArgumentException("Private key must be PKCS#8 PEM");
            }
            String encoded =
                    pem.substring(pem.indexOf(prefix) + prefix.length(), pem.indexOf(suffix))
                            .replaceAll("\\s", "");
            RSAPrivateKey key =
                    (RSAPrivateKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePrivate(
                                            new PKCS8EncodedKeySpec(
                                                    Base64.getDecoder().decode(encoded)));
            requireStrong(key.getModulus().bitLength());
            return key;
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load JWT private key", ex);
        }
    }

    private Map<String, RSAPublicKey> loadPublicKeys(String location) {
        try {
            JWKSet jwkSet = JWKSet.parse(Files.readString(Path.of(location)));
            Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
            for (JWK jwk : jwkSet.getKeys()) {
                if (!(jwk instanceof RSAKey rsaKey)
                        || rsaKey.getKeyID() == null
                        || rsaKey.getKeyID().isBlank()
                        || rsaKey.isPrivate()) {
                    throw new IllegalArgumentException("JWKS must contain named public RSA keys");
                }
                RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
                requireStrong(publicKey.getModulus().bitLength());
                if (keys.putIfAbsent(rsaKey.getKeyID(), publicKey) != null) {
                    throw new IllegalArgumentException("Duplicate JWT key id");
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("JWKS is empty");
            }
            return Map.copyOf(keys);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load JWT public JWKS", ex);
        }
    }

    private static void requireStrong(int bits) {
        if (bits < 2048) {
            throw new IllegalArgumentException("JWT RSA keys must be at least 2048 bits");
        }
    }

    private Duration requiredDuration(String name, Duration expected) {
        Duration value;
        try {
            value = Duration.parse(required(name));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid authentication duration setting: " + name, ex);
        }
        if (!expected.equals(value)) {
            throw new IllegalStateException("Authentication duration violates policy: " + name);
        }
        return value;
    }

    private String requiredIdentifier(String name, String expected) {
        String value = required(name);
        if (!expected.equals(value)) {
            throw new IllegalStateException("Authentication identifier violates policy: " + name);
        }
        return value;
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required authentication setting is missing: " + name);
        }
        return value;
    }
}
