package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwtTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T12:00:00Z");
    private KeyPair active;
    private KeyPair previous;
    private JwtTokenService service;

    @BeforeEach
    void setUp() {
        active = TestRsaKeys.generate(2048);
        previous = TestRsaKeys.generate(2048);
        service =
                new JwtTokenService(
                        "active",
                        TestRsaKeys.privateKey(active),
                        Map.of(
                                "active", TestRsaKeys.publicKey(active),
                                "previous", TestRsaKeys.publicKey(previous)),
                        Duration.ofMinutes(15),
                        Duration.ofDays(30),
                        Duration.ofSeconds(30),
                        "meonggocuisine-auth",
                        "meonggocuisine-api",
                        "meonggocuisine-android",
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesStrictAccessTokenAndVerifiesPrincipal() throws Exception {
        JwtTokenService.IssuedToken issued = service.issue(7, 11, NOW);
        SignedJWT jwt = SignedJWT.parse(issued.value());

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("active");
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet())
                .containsExactlyInAnyOrder(
                        "iss", "aud", "sub", "sid", "client_id", "jti", "iat", "nbf", "exp");
        assertThat(jwt.getJWTClaimsSet().getJWTID()).matches("[A-Za-z0-9_-]{22}");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(issued.toString()).doesNotContain(issued.value());
        assertThat(service.verify(issued.value())).isEqualTo(new AuthPrincipal(7, 11));
        assertThat(service.refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void reportsTheExpirationEncodedInJwtForFractionalSecondIssuance() throws Exception {
        JwtTokenService.IssuedToken issued = service.issue(7, 11, NOW.plusMillis(987));

        assertThat(issued.expiresAt())
                .isEqualTo(
                        SignedJWT.parse(issued.value())
                                .getJWTClaimsSet()
                                .getExpirationTime()
                                .toInstant());
    }

    @Test
    void acceptsPreviousPublicKeyWithinTimeWindow() throws Exception {
        assertThat(service.verify(signed("previous", previous, NOW.minusSeconds(899))))
                .isEqualTo(new AuthPrincipal(7, 11));
    }

    @Test
    void rejectsUnknownKidWrongTypeAndInvalidIds() throws Exception {
        assertInvalid(signed("unknown", active, NOW));
        assertInvalid(
                signedWith("active", active, "AT+JWT", "7", "11", NOW, NOW, NOW.plusSeconds(900)));
        assertInvalid(
                signedWith("active", active, "at+jwt", "+7", "11", NOW, NOW, NOW.plusSeconds(900)));
        assertInvalid(
                signedWith("active", active, "at+jwt", "7", "x", NOW, NOW, NOW.plusSeconds(900)));
    }

    @Test
    void rejectsWrongSignature() throws Exception {
        assertInvalid(signed("active", previous, NOW));
    }

    @Test
    void rejectsNoneAndNonRs256Algorithms() throws Exception {
        assertInvalid(
                new PlainJWT(
                                new com.nimbusds.jose.PlainHeader.Builder()
                                        .type(new JOSEObjectType("at+jwt"))
                                        .customParam("kid", "active")
                                        .build(),
                                claims(
                                        null,
                                        "meonggocuisine-auth",
                                        "meonggocuisine-api",
                                        "meonggocuisine-android"))
                        .serialize());
        SignedJWT hs256 =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256)
                                .type(new JOSEObjectType("at+jwt"))
                                .keyID("active")
                                .build(),
                        claims(
                                null,
                                "meonggocuisine-auth",
                                "meonggocuisine-api",
                                "meonggocuisine-android"));
        hs256.sign(new MACSigner(new byte[32]));
        assertInvalid(hs256.serialize());
    }

    @Test
    void rejectsWrongIssuerAudienceAndClientId() throws Exception {
        assertInvalid(
                signedClaims(
                        claims(null, "other", "meonggocuisine-api", "meonggocuisine-android")));
        assertInvalid(
                signedClaims(
                        claims(null, "meonggocuisine-auth", "other", "meonggocuisine-android")));
        assertInvalid(
                signedClaims(claims(null, "meonggocuisine-auth", "meonggocuisine-api", "other")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "sid", "client_id", "jti", "iat", "nbf", "exp"})
    void rejectsEachMissingRequiredClaim(String missingClaim) throws Exception {
        assertInvalid(
                signedClaims(
                        claims(
                                missingClaim,
                                "meonggocuisine-auth",
                                "meonggocuisine-api",
                                "meonggocuisine-android")));
    }

    @Test
    void enforcesThirtySecondExpiryAndNotBeforeSkewBoundaries() throws Exception {
        assertThat(service.verify(signed("active", active, NOW.plusSeconds(30))))
                .isEqualTo(new AuthPrincipal(7, 11));
        assertInvalid(signed("active", active, NOW.plusSeconds(31)));
        assertThat(service.verify(signed("active", active, NOW.minusSeconds(930))))
                .isEqualTo(new AuthPrincipal(7, 11));
        assertInvalid(signed("active", active, NOW.minusSeconds(931)));
    }

    @Test
    void rejectsInconsistentAccessTokenTimes() throws Exception {
        assertInvalid(
                signedWith(
                        "active",
                        active,
                        "at+jwt",
                        "7",
                        "11",
                        NOW,
                        NOW.plusSeconds(1),
                        NOW.plusSeconds(900)));
        assertInvalid(
                signedWith("active", active, "at+jwt", "7", "11", NOW, NOW, NOW.plusSeconds(901)));
    }

    private String signed(String kid, KeyPair pair, Instant issuedAt) throws Exception {
        return signedWith(
                kid, pair, "at+jwt", "7", "11", issuedAt, issuedAt, issuedAt.plusSeconds(900));
    }

    private String signedWith(
            String kid,
            KeyPair pair,
            String type,
            String subject,
            String sessionId,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt)
            throws Exception {
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder(
                                claims(
                                        null,
                                        "meonggocuisine-auth",
                                        "meonggocuisine-api",
                                        "meonggocuisine-android"))
                        .subject(subject)
                        .claim("sid", sessionId)
                        .issueTime(Date.from(issuedAt))
                        .notBeforeTime(Date.from(notBefore))
                        .expirationTime(Date.from(expiresAt))
                        .build();
        return signedClaims(kid, pair, type, claims);
    }

    private String signedClaims(JWTClaimsSet claims) throws Exception {
        return signedClaims("active", active, "at+jwt", claims);
    }

    private String signedClaims(String kid, KeyPair pair, String type, JWTClaimsSet claims)
            throws Exception {
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .type(new JOSEObjectType(type))
                                .keyID(kid)
                                .build(),
                        claims);
        jwt.sign(new RSASSASigner(pair.getPrivate()));
        return jwt.serialize();
    }

    private JWTClaimsSet claims(
            String missingClaim, String issuer, String audience, String clientId) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        if (!"iss".equals(missingClaim)) {
            builder.issuer(issuer);
        }
        if (!"aud".equals(missingClaim)) {
            builder.audience(List.of(audience));
        }
        if (!"sub".equals(missingClaim)) {
            builder.subject("7");
        }
        if (!"sid".equals(missingClaim)) {
            builder.claim("sid", "11");
        }
        if (!"client_id".equals(missingClaim)) {
            builder.claim("client_id", clientId);
        }
        if (!"jti".equals(missingClaim)) {
            builder.jwtID("AAAAAAAAAAAAAAAAAAAAAA");
        }
        if (!"iat".equals(missingClaim)) {
            builder.issueTime(Date.from(NOW));
        }
        if (!"nbf".equals(missingClaim)) {
            builder.notBeforeTime(Date.from(NOW));
        }
        if (!"exp".equals(missingClaim)) {
            builder.expirationTime(Date.from(NOW.plusSeconds(900)));
        }
        return builder.build();
    }

    private void assertInvalid(String raw) {
        assertThatThrownBy(() -> service.verify(raw))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTHENTICATION_REQUIRED);
    }
}
