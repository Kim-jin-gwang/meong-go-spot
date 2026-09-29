package com.meonggo.backend.push.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PushTokenProtection {
    private static final byte[] AAD = "mgbj:fcm-token:v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String currentKeyId;
    private final Map<String, byte[]> encryptionKeys;
    private final byte[] lookupKey;

    public PushTokenProtection(
            String currentKeyId, Map<String, byte[]> encryptionKeys, byte[] lookupKey) {
        if (currentKeyId == null
                || encryptionKeys == null
                || !encryptionKeys.containsKey(currentKeyId)
                || encryptionKeys.isEmpty()
                || encryptionKeys.size() > 2
                || lookupKey == null
                || lookupKey.length < 32) {
            throw new IllegalArgumentException("FCM token protection keys are invalid");
        }
        Map<String, byte[]> copied = new HashMap<>();
        encryptionKeys.forEach(
                (keyId, key) -> {
                    if (keyId == null
                            || !keyId.matches("[A-Za-z0-9_-]{1,64}")
                            || key == null
                            || key.length != 32
                            || java.util.Arrays.equals(key, lookupKey)
                            || copied.values().stream()
                                    .anyMatch(existing -> java.util.Arrays.equals(existing, key))) {
                        throw new IllegalArgumentException("FCM token protection keys are invalid");
                    }
                    copied.put(keyId, key.clone());
                });
        this.currentKeyId = currentKeyId;
        this.encryptionKeys = Map.copyOf(copied);
        this.lookupKey = lookupKey.clone();
    }

    public ProtectedToken protect(String token) {
        validateToken(token);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, encryptionKeys.get(currentKeyId), nonce);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new ProtectedToken(
                    "enc:v1:"
                            + currentKeyId
                            + ":"
                            + encoder.encodeToString(nonce)
                            + ":"
                            + encoder.encodeToString(encrypted),
                    lookupHash(token));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("FCM token could not be protected");
        }
    }

    public String decrypt(String envelope) {
        try {
            String[] parts = envelope == null ? new String[0] : envelope.split(":", -1);
            if (parts.length != 5
                    || !"enc".equals(parts[0])
                    || !"v1".equals(parts[1])
                    || !encryptionKeys.containsKey(parts[2])) throw new GeneralSecurityException();
            byte[] nonce = decode(parts[3]);
            byte[] encrypted = decode(parts[4]);
            if (nonce.length != 12 || encrypted.length < 17 || encrypted.length > 4112)
                throw new GeneralSecurityException();
            String token =
                    new String(
                            cipher(Cipher.DECRYPT_MODE, encryptionKeys.get(parts[2]), nonce)
                                    .doFinal(encrypted),
                            StandardCharsets.UTF_8);
            validateToken(token);
            return token;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("FCM token protection value is invalid");
        }
    }

    private String lookupHash(String token) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
    }

    private static void validateToken(String token) {
        if (token == null || token.isBlank() || token.length() > 4096)
            throw new IllegalArgumentException("FCM token is invalid");
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        return cipher;
    }

    private static byte[] decode(String value) {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))
            throw new IllegalArgumentException();
        return decoded;
    }

    public record ProtectedToken(String ciphertext, String lookupHash) {}
}
