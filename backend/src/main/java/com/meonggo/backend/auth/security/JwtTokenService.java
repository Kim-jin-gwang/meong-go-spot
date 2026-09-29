package com.meonggo.backend.auth.security;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class JwtTokenService {
    private static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String activeKid;
    private final RSAPrivateKey privateKey;
    private final Map<String, RSAPublicKey> publicKeys;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration clockSkew;
    private final String issuer;
    private final String audience;
    private final String clientId;
    private final Clock clock;

    public JwtTokenService(
            String activeKid,
            RSAPrivateKey privateKey,
            Map<String, RSAPublicKey> publicKeys,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            Duration clockSkew,
            String issuer,
            String audience,
            String clientId,
            Clock clock) {
        this.activeKid = activeKid;
        this.privateKey = privateKey;
        this.publicKeys = Map.copyOf(publicKeys);
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.clockSkew = clockSkew;
        this.issuer = issuer;
        this.audience = audience;
        this.clientId = clientId;
        this.clock = clock;
    }

    public IssuedToken issue(long memberId, long sessionId, Instant now) {
        if (memberId <= 0 || sessionId <= 0 || now == null) {
            throw new IllegalArgumentException(
                    "Token issuance requires positive identifiers and time");
        }
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(accessTokenTtl);
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(Long.toString(memberId))
                        .claim("sid", Long.toString(sessionId))
                        .claim("client_id", clientId)
                        .jwtID(randomIdentifier())
                        .issueTime(Date.from(issuedAt))
                        .notBeforeTime(Date.from(issuedAt))
                        .expirationTime(Date.from(expiresAt))
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .type(ACCESS_TOKEN_TYPE)
                                .keyID(activeKid)
                                .build(),
                        claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
            return new IssuedToken(jwt.serialize(), expiresAt);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot sign access token", ex);
        }
    }

    public AuthPrincipal verify(String raw) {
        try {
            SignedJWT jwt = SignedJWT.parse(raw);
            JWSHeader header = jwt.getHeader();
            RSAPublicKey publicKey = publicKeys.get(header.getKeyID());
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())
                    || header.getType() == null
                    || !"at+jwt".equals(header.getType().toString())
                    || publicKey == null
                    || !jwt.verify(new RSASSAVerifier(publicKey))) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            validateClaims(claims);
            return new AuthPrincipal(
                    positiveId(claims.getSubject()), positiveId(claims.getStringClaim("sid")));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }

    private void validateClaims(JWTClaimsSet claims) throws java.text.ParseException {
        Instant now = clock.instant();
        Date issuedAt = claims.getIssueTime();
        Date notBefore = claims.getNotBeforeTime();
        Date expiresAt = claims.getExpirationTime();
        String jwtId = claims.getJWTID();
        if (!issuer.equals(claims.getIssuer())
                || claims.getAudience() == null
                || !claims.getAudience().contains(audience)
                || !clientId.equals(claims.getStringClaim("client_id"))
                || issuedAt == null
                || notBefore == null
                || expiresAt == null
                || jwtId == null
                || !jwtId.matches("[A-Za-z0-9_-]{22}")
                || !notBefore.equals(issuedAt)
                || !expiresAt.toInstant().equals(issuedAt.toInstant().plus(accessTokenTtl))
                || notBefore.toInstant().isAfter(now.plus(clockSkew))
                || expiresAt.toInstant().isBefore(now.minus(clockSkew))) {
            throw invalid();
        }
    }

    private static long positiveId(String raw) {
        if (raw == null || !raw.matches("[1-9][0-9]*")) {
            throw invalid();
        }
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw invalid();
        }
        return value;
    }

    private static BusinessException invalid() {
        return new BusinessException(AuthErrorCode.AUTHENTICATION_REQUIRED);
    }

    private static String randomIdentifier() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[value=redacted, expiresAt=" + expiresAt + "]";
        }
    }
}
