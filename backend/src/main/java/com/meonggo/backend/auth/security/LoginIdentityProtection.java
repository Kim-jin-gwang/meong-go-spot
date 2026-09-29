package com.meonggo.backend.auth.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class LoginIdentityProtection {
    private final byte[] accountKey;
    private final byte[] ipKey;

    public LoginIdentityProtection(byte[] accountKey, byte[] ipKey) {
        if (accountKey == null
                || ipKey == null
                || accountKey.length < 32
                || ipKey.length < 32
                || MessageDigest.isEqual(accountKey, ipKey)) {
            throw new IllegalArgumentException(
                    "Login HMAC keys must be independent and at least 32 bytes");
        }
        this.accountKey = accountKey.clone();
        this.ipKey = ipKey.clone();
    }

    public String accountHash(String canonicalId) {
        return hmac(accountKey, canonicalId.getBytes(StandardCharsets.UTF_8));
    }

    public String ipHash(byte[] canonicalIp) {
        if (canonicalIp.length != 4 && canonicalIp.length != 16) {
            throw new IllegalArgumentException("Canonical network address is required");
        }
        byte[] prefix = "login-ip\0".getBytes(StandardCharsets.UTF_8);
        byte[] input =
                ByteBuffer.allocate(prefix.length + 1 + canonicalIp.length)
                        .put(prefix)
                        .put((byte) (canonicalIp.length == 4 ? 4 : 6))
                        .put(canonicalIp)
                        .array();
        return hmac(ipKey, input);
    }

    private String hmac(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot protect login identity");
        }
    }
}
