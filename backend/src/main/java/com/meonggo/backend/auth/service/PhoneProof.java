package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.exception.PhoneErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 원문 토큰은 응답에서만 전달하고 Redis에는 selector와 secret의 hash를 전달한다. */
final class PhoneProof {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String selector;
    private final byte[] secret;

    private PhoneProof(String selector, byte[] secret) {
        this.selector = selector;
        this.secret = secret.clone();
    }

    static PhoneProof generate() {
        byte[] selector = new byte[16];
        byte[] secret = new byte[32];
        RANDOM.nextBytes(selector);
        RANDOM.nextBytes(secret);
        return new PhoneProof(ENCODER.encodeToString(selector), secret);
    }

    static PhoneProof parse(String token) {
        if (token == null || !token.matches("pv1\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}")) {
            throw invalid();
        }
        String[] parts = token.split("\\.");
        try {
            byte[] selector = Base64.getUrlDecoder().decode(parts[1]);
            byte[] secret = Base64.getUrlDecoder().decode(parts[2]);
            if (!ENCODER.encodeToString(selector).equals(parts[1])
                    || !ENCODER.encodeToString(secret).equals(parts[2])) {
                throw invalid();
            }
            return new PhoneProof(parts[1], secret);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    String selector() {
        return selector;
    }

    String token() {
        return "pv1." + selector + "." + ENCODER.encodeToString(secret);
    }

    String secretHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 unavailable");
        }
    }

    static BusinessException invalid() {
        return new BusinessException(PhoneErrorCode.INVALID_VERIFICATION);
    }
}
