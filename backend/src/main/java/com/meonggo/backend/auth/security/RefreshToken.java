package com.meonggo.backend.auth.security;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class RefreshToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9_-]{22}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final String selector;
    private final String secret;

    private RefreshToken(String selector, String secret) {
        this.selector = selector;
        this.secret = secret;
    }

    public static RefreshToken create() {
        return new RefreshToken(random(16), random(32));
    }

    public static RefreshToken rotate(String selector) {
        if (selector == null || !SELECTOR.matcher(selector).matches() || !canonical(selector, 16)) {
            throw invalid();
        }
        return new RefreshToken(selector, random(32));
    }

    public static RefreshToken parse(String raw) {
        if (raw == null || raw.length() != 69) {
            throw invalid();
        }
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 3
                || !"v1".equals(parts[0])
                || !SELECTOR.matcher(parts[1]).matches()
                || !SECRET.matcher(parts[2]).matches()
                || !canonical(parts[1], 16)
                || !canonical(parts[2], 32)) {
            throw invalid();
        }
        return new RefreshToken(parts[1], parts[2]);
    }

    public String value() {
        return "v1." + selector + "." + secret;
    }

    public String selector() {
        return selector;
    }

    public String secretHash() {
        return HexFormat.of().formatHex(hash(secret));
    }

    public boolean matches(String storedHash) {
        if (storedHash == null || !HASH.matcher(storedHash).matches()) {
            return false;
        }
        return MessageDigest.isEqual(hash(secret), HexFormat.of().parseHex(storedHash));
    }

    @Override
    public String toString() {
        return "RefreshToken[redacted]";
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return ENCODER.encodeToString(value);
    }

    private static boolean canonical(String value, int expectedBytes) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == expectedBytes && ENCODER.encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static byte[] hash(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(AuthErrorCode.INVALID_SESSION);
    }
}
