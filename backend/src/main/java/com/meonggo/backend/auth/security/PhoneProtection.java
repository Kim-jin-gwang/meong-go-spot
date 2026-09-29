package com.meonggo.backend.auth.security;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Protects phone numbers and related verification identifiers with purpose-specific keys. */
public final class PhoneProtection {
    private static final byte[] PHONE_AAD = "mgbj:member-phone:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IP_DOMAIN = "otp-send-ip\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String currentKid;
    private final Map<String, byte[]> encryptionKeys;
    private final byte[] lookupKey;
    private final byte[] otpKey;
    private final byte[] ipKey;

    public PhoneProtection(
            String currentKid,
            Map<String, byte[]> encryptionKeys,
            byte[] lookupKey,
            byte[] otpKey,
            byte[] ipKey) {
        if (currentKid == null
                || currentKid.isBlank()
                || !isValidKid(currentKid)
                || encryptionKeys == null
                || !encryptionKeys.containsKey(currentKid)
                || encryptionKeys.isEmpty()
                || encryptionKeys.size() > 2) {
            throw new IllegalArgumentException("전화번호 암호화 키 설정이 올바르지 않습니다.");
        }
        Map<String, byte[]> copiedKeys = new HashMap<>();
        encryptionKeys.forEach(
                (kid, key) -> {
                    if (kid == null
                            || kid.isBlank()
                            || !isValidKid(kid)
                            || key == null
                            || key.length != 32) {
                        throw new IllegalArgumentException("전화번호 암호화 키 설정이 올바르지 않습니다.");
                    }
                    copiedKeys.put(kid, key.clone());
                });
        requireHmacKey(lookupKey);
        requireHmacKey(otpKey);
        requireHmacKey(ipKey);
        if (containsDuplicateEncryptionKey(copiedKeys)
                || Arrays.equals(lookupKey, otpKey)
                || Arrays.equals(lookupKey, ipKey)
                || Arrays.equals(otpKey, ipKey)
                || copiedKeys.values().stream()
                        .anyMatch(
                                key ->
                                        Arrays.equals(key, lookupKey)
                                                || Arrays.equals(key, otpKey)
                                                || Arrays.equals(key, ipKey))) {
            throw new IllegalArgumentException("목적별 보호 키를 재사용할 수 없습니다.");
        }
        this.currentKid = currentKid;
        this.encryptionKeys = Map.copyOf(copiedKeys);
        this.lookupKey = lookupKey.clone();
        this.otpKey = otpKey.clone();
        this.ipKey = ipKey.clone();
    }

    private static boolean containsDuplicateEncryptionKey(Map<String, byte[]> keys) {
        byte[][] values = keys.values().toArray(byte[][]::new);
        return values.length == 2 && Arrays.equals(values[0], values[1]);
    }

    private static boolean isValidKid(String kid) {
        return kid.matches("[A-Za-z0-9_-]+");
    }

    public String normalize(String value) {
        if (value != null && value.matches("010[0-9]{8}")) {
            return "+82" + value.substring(1);
        }
        if (value != null && value.matches("\\+8210[0-9]{8}")) {
            return value;
        }
        throw new InputValidationException("phoneNumber", "휴대전화 번호 형식을 확인해 주세요.");
    }

    public String lookupHash(String e164) {
        requireE164(e164);
        return hmacHex(lookupKey, e164.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(String e164) {
        requireE164(e164);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKeys.get(currentKid), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PHONE_AAD);
            byte[] encrypted = cipher.doFinal(e164.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "enc:v1:"
                    + currentKid
                    + ":"
                    + encoder.encodeToString(nonce)
                    + ":"
                    + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("휴대전화 번호를 보호할 수 없습니다.", exception);
        }
    }

    public String decrypt(String envelope) {
        try {
            String[] parts = envelope == null ? new String[0] : envelope.split(":", -1);
            if (parts.length != 5 || !parts[0].equals("enc") || !parts[1].equals("v1")) {
                throw new GeneralSecurityException("invalid envelope");
            }
            byte[] key = encryptionKeys.get(parts[2]);
            if (key == null) {
                throw new GeneralSecurityException("unknown key");
            }
            byte[] nonce = decodeCanonical(parts[3], 16);
            byte[] encrypted = decodeCanonical(parts[4], 39);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PHONE_AAD);
            String plaintext = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            requireE164(plaintext);
            return plaintext;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("휴대전화 번호 보호값을 확인할 수 없습니다.", exception);
        }
    }

    public String otpHash(String verificationId, String otp) {
        if (verificationId == null
                || verificationId.isBlank()
                || otp == null
                || !otp.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("인증 정보 형식이 올바르지 않습니다.");
        }
        byte[] verification = verificationId.getBytes(StandardCharsets.UTF_8);
        byte[] code = otp.getBytes(StandardCharsets.US_ASCII);
        return hmacHex(
                otpKey,
                ByteBuffer.allocate(verification.length + 1 + code.length)
                        .put(verification)
                        .put((byte) 0)
                        .put(code)
                        .array());
    }

    public String ipHash(byte[] canonicalAddress) {
        if (canonicalAddress == null
                || (canonicalAddress.length != 4 && canonicalAddress.length != 16)) {
            throw new IllegalArgumentException("IP 주소 형식이 올바르지 않습니다.");
        }
        return hmacHex(
                ipKey,
                ByteBuffer.allocate(IP_DOMAIN.length + 1 + canonicalAddress.length)
                        .put(IP_DOMAIN)
                        .put((byte) (canonicalAddress.length == 4 ? 4 : 6))
                        .put(canonicalAddress)
                        .array());
    }

    public void requireDistinctKeys(byte[]... candidateKeys) {
        for (byte[] candidate : candidateKeys) {
            if (candidate == null
                    || encryptionKeys.values().stream()
                            .anyMatch(key -> Arrays.equals(key, candidate))
                    || Arrays.equals(lookupKey, candidate)
                    || Arrays.equals(otpKey, candidate)
                    || Arrays.equals(ipKey, candidate)) {
                throw new IllegalArgumentException("목적별 보호 키를 재사용할 수 없습니다.");
            }
        }
    }

    private static void requireE164(String value) {
        if (value == null
                || (!value.matches("\\+8210[0-9]{8}")
                        && !TestPhoneSignupBypass.NORMALIZED_PHONE.equals(value))) {
            throw new IllegalArgumentException("정규화된 휴대전화 번호가 필요합니다.");
        }
    }

    private static byte[] decodeCanonical(String value, int encodedLength)
            throws GeneralSecurityException {
        if (value.length() != encodedLength || !value.matches("[A-Za-z0-9_-]+")) {
            throw new GeneralSecurityException("invalid envelope");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw new GeneralSecurityException("non-canonical envelope");
        }
        return decoded;
    }

    private static void requireHmacKey(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("HMAC 키는 32바이트 이상이어야 합니다.");
        }
    }

    private static String hmacHex(byte[] key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("보호 해시를 계산할 수 없습니다.", exception);
        }
    }
}
