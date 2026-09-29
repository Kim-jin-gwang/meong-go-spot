package com.meonggo.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class SessionTokenConfigurationTest {
    @TempDir Path directory;
    private static KeyPair current;
    private static KeyPair other;
    private static KeyPair weak;
    private MockEnvironment environment;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        current = generator.generateKeyPair();
        other = generator.generateKeyPair();
        generator.initialize(1024);
        weak = generator.generateKeyPair();
    }

    @BeforeEach
    void configure() throws Exception {
        environment =
                new MockEnvironment()
                        .withProperty("AUTH_JWT_ACTIVE_KID", "current")
                        .withProperty("AUTH_ACCESS_TOKEN_TTL", "PT15M")
                        .withProperty("AUTH_REFRESH_TOKEN_TTL", "P30D")
                        .withProperty("AUTH_JWT_CLOCK_SKEW", "PT30S")
                        .withProperty("AUTH_JWT_ISSUER", "meonggocuisine-auth")
                        .withProperty("AUTH_JWT_AUDIENCE", "meonggocuisine-api")
                        .withProperty("AUTH_JWT_CLIENT_ID", "meonggocuisine-android");
        privateKey(current);
        publicKeys(new JWKSet(publicKey(current, "current")));
    }

    @Test
    void loadsMatchingCurrentAndPreviousPublicKeys() throws Exception {
        publicKeys(
                new JWKSet(List.of(publicKey(current, "current"), publicKey(other, "previous"))));
        var tokens = load();
        assertThat(tokens.verify(tokens.issue(1, 2, Instant.now()).value()).memberId())
                .isEqualTo(1);
    }

    @Test
    void missingSettingFailsBeforeApplicationCanAcceptRequests() {
        environment.setProperty("AUTH_JWT_PRIVATE_KEY_PATH", "");
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsIdentifierChangesThatWouldBreakAndroidTokenContract() {
        for (String name : List.of("AUTH_JWT_ISSUER", "AUTH_JWT_AUDIENCE", "AUTH_JWT_CLIENT_ID")) {
            String original = environment.getProperty(name);
            environment.setProperty(name, "different-identifier");
            assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
            environment.setProperty(name, original);
        }
    }

    @Test
    void rejectsUnknownActiveKeyAndDifferentKeyPair() throws Exception {
        environment.setProperty("AUTH_JWT_ACTIVE_KID", "absent");
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
        environment.setProperty("AUTH_JWT_ACTIVE_KID", "current");
        publicKeys(new JWKSet(publicKey(other, "current")));
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPrivateAndDuplicateJwksEntries() throws Exception {
        RSAKey privateEntry =
                new RSAKey.Builder((RSAPublicKey) current.getPublic())
                        .privateKey((RSAPrivateKey) current.getPrivate())
                        .keyID("current")
                        .build();
        publicKeys(new JWKSet(privateEntry), false);
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
        publicKeys(new JWKSet(List.of(publicKey(current, "current"), publicKey(other, "current"))));
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWeakPrivateAndPreviousPublicKeys() throws Exception {
        publicKeys(new JWKSet(List.of(publicKey(current, "current"), publicKey(weak, "previous"))));
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
        privateKey(weak);
        publicKeys(new JWKSet(publicKey(weak, "current")));
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonPkcs8AndPolicyViolatingDurations() throws Exception {
        Files.writeString(directory.resolve("private.pem"), "invalid-test-key");
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
        privateKey(current);
        environment.setProperty("AUTH_ACCESS_TOKEN_TTL", "PT1H");
        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class);
    }

    private com.meonggo.backend.auth.security.JwtTokenService load() {
        return new SessionTokenConfiguration(environment).jwtTokenService(Clock.systemUTC());
    }

    private RSAKey publicKey(KeyPair pair, String kid) {
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID(kid).build();
    }

    private void privateKey(KeyPair pair) throws Exception {
        Path file = directory.resolve("private.pem");
        Files.writeString(
                file,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n");
        environment.setProperty("AUTH_JWT_PRIVATE_KEY_PATH", file.toString());
    }

    private void publicKeys(JWKSet keys) throws Exception {
        publicKeys(keys, true);
    }

    private void publicKeys(JWKSet keys, boolean publicOnly) throws Exception {
        Path file = directory.resolve("jwks.json");
        Files.writeString(file, keys.toString(publicOnly));
        environment.setProperty("AUTH_JWT_PUBLIC_JWKS_PATH", file.toString());
    }
}
