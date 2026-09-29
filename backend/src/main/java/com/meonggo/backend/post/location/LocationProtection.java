package com.meonggo.backend.post.location;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Purpose-separated authenticated encryption for normalized exact-location text. */
public final class LocationProtection {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PLAINTEXT_BYTES = 800;
    private final String currentKid;
    private final Map<String, byte[]> keys;

    public LocationProtection(String currentKid, Map<String, byte[]> keys) {
        if (currentKid == null
                || keys == null
                || !keys.containsKey(currentKid)
                || keys.isEmpty()
                || keys.size() > 2) {
            throw invalidKeys();
        }
        Map<String, byte[]> copied = new HashMap<>();
        keys.forEach(
                (kid, key) -> {
                    if (kid == null
                            || !kid.matches("[A-Za-z0-9_-]{1,64}")
                            || key == null
                            || key.length != 32
                            || copied.values().stream()
                                    .anyMatch(existing -> Arrays.equals(existing, key))) {
                        throw invalidKeys();
                    }
                    copied.put(kid, key.clone());
                });
        this.currentKid = currentKid;
        this.keys = Map.copyOf(copied);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > MAX_PLAINTEXT_BYTES) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(currentKid), nonce);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "enc:v1:"
                    + currentKid
                    + ":"
                    + encoder.encodeToString(nonce)
                    + ":"
                    + encoder.encodeToString(cipher.doFinal(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("정확한 위치를 보호할 수 없습니다.");
        }
    }

    public String decrypt(String envelope) {
        try {
            if (envelope == null || envelope.length() > 1200) {
                throw new IllegalArgumentException();
            }
            String[] parts = envelope.split(":", -1);
            if (parts.length != 5
                    || !parts[0].equals("enc")
                    || !parts[1].equals("v1")
                    || !keys.containsKey(parts[2])) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = decode(parts[3]);
            byte[] encrypted = decode(parts[4]);
            if (nonce.length != 12
                    || encrypted.length < 17
                    || encrypted.length > MAX_PLAINTEXT_BYTES + 16) {
                throw new IllegalArgumentException();
            }
            byte[] plaintext =
                    cipher(Cipher.DECRYPT_MODE, keys.get(parts[2]), nonce).doFinal(encrypted);
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext))
                    .toString();
        } catch (Exception exception) {
            throw new IllegalStateException("정확한 위치 보호값을 확인할 수 없습니다.");
        }
    }

    @Override
    public String toString() {
        return "LocationProtection[redacted]";
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce)
            throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("mgbj:exact-location:v1".getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] decode(String encoded) {
        if (!encoded.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException();
        }
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
            throw new IllegalArgumentException();
        }
        return decoded;
    }

    private static IllegalArgumentException invalidKeys() {
        return new IllegalArgumentException("위치 암호화 키 설정이 올바르지 않습니다.");
    }
}
