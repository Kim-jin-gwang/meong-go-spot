package com.meonggo.backend.auth.security;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Applies the canonical signup identity and password policy before expensive work begins. */
public final class SignupInputPolicy {

    private static final int MIN_PASSWORD_CODE_POINTS = 8;
    private static final int MAX_PASSWORD_CODE_POINTS = 64;
    private static final String BLOCKLIST_RESOURCE =
            "/security/password-blocklist/pwdb-top-100000.txt";
    private static final long BLOCKLIST_BYTES = 828_498;
    private static final int BLOCKLIST_LINES = 100_000;
    private static final String BLOCKLIST_SHA256 =
            "07f876a616f08fb2cc5c3e0ce04e4a6d1123380580472b0997baebc4e8226977";
    private static final int PRE_NORMALIZATION_LIMIT = 256;
    private static final String SERVICE_NAME = "멍고반점";

    private final Set<String> blockedPasswords;

    public SignupInputPolicy() {
        this(openPinnedBlocklist(), BLOCKLIST_BYTES, BLOCKLIST_LINES, BLOCKLIST_SHA256);
    }

    public SignupInputPolicy(
            InputStream blocklist, long expectedBytes, int expectedLines, String expectedSha256) {
        this.blockedPasswords =
                loadBlocklist(blocklist, expectedBytes, expectedLines, expectedSha256);
    }

    public String canonicalLoginId(String value) {
        requirePresent(value, "loginId");
        requirePreNormalizationLimit(value, "loginId");
        String canonical = normalizeCaseInsensitive(value);
        int length = canonical.codePointCount(0, canonical.length());
        if (length < 1 || length > 50 || containsForbidden(canonical, true)) {
            throw invalid("loginId", "로그인 ID 형식을 확인해 주세요.");
        }
        return canonical;
    }

    public String normalizePassword(String value, String canonicalLoginId) {
        requirePresent(value, "password");
        String normalized = normalizeLoginPassword(value);
        if (normalized == null) {
            throw invalid(
                    "password",
                    "비밀번호는 공백 없이 "
                            + MIN_PASSWORD_CODE_POINTS
                            + "~"
                            + MAX_PASSWORD_CODE_POINTS
                            + "자로 입력해 주세요.");
        }
        String comparison = normalizeCaseInsensitive(normalized);
        if (blockedPasswords.contains(comparison)
                || comparison.equals(normalizeCaseInsensitive(SERVICE_NAME))
                || (canonicalLoginId != null
                        && comparison.equals(normalizeCaseInsensitive(canonicalLoginId)))) {
            throw invalid("password", "더 강한 다른 비밀번호를 사용해 주세요.");
        }
        return normalized;
    }

    /** 형식이 잘못된 기존 입력은 null로 표시해 로그인에서 dummy 비교를 수행한다. */
    public String normalizeLoginPassword(String value) {
        if (value == null) {
            throw invalid("password", "password 값을 입력해 주세요.");
        }
        requirePreNormalizationLimit(value, "password");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        int length = normalized.codePointCount(0, normalized.length());
        return length < MIN_PASSWORD_CODE_POINTS
                        || length > MAX_PASSWORD_CODE_POINTS
                        || containsForbidden(normalized, true)
                ? null
                : normalized;
    }

    public String normalizeNickname(String value) {
        requirePresent(value, "nickname");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 30 || containsInvalidDisplayCharacter(normalized)) {
            throw invalid("nickname", "닉네임은 1~30자로 입력해 주세요.");
        }
        return normalized;
    }

    private static InputStream openPinnedBlocklist() {
        InputStream stream = SignupInputPolicy.class.getResourceAsStream(BLOCKLIST_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("비밀번호 차단 목록을 읽을 수 없습니다.");
        }
        return stream;
    }

    private static Set<String> loadBlocklist(
            InputStream stream, long expectedBytes, int expectedLines, String expectedSha256) {
        if (stream == null) {
            throw new IllegalStateException("비밀번호 차단 목록을 읽을 수 없습니다.");
        }
        try (stream) {
            byte[] bytes = stream.readAllBytes();
            String actualHash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != expectedBytes || !actualHash.equals(expectedSha256)) {
                throw new IllegalStateException("비밀번호 차단 목록 무결성 검증에 실패했습니다.");
            }
            String text = decodeUtf8(bytes);
            String[] lines = text.split("\n", -1);
            int lineCount = lines.length - (text.endsWith("\n") ? 1 : 0);
            if (lineCount != expectedLines) {
                throw new IllegalStateException("비밀번호 차단 목록 줄 수 검증에 실패했습니다.");
            }
            Set<String> result = new HashSet<>(expectedLines * 2);
            for (int index = 0; index < lineCount; index++) {
                String line = lines[index];
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                result.add(normalizeCaseInsensitive(line));
            }
            return Set.copyOf(result);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("비밀번호 차단 목록을 검증할 수 없습니다.", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("비밀번호 차단 목록 문자 인코딩이 올바르지 않습니다.", exception);
        }
    }

    private static String normalizeCaseInsensitive(String value) {
        return Normalizer.normalize(
                Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
                Normalizer.Form.NFC);
    }

    private static boolean containsForbidden(String value, boolean rejectWhitespace) {
        return value.codePoints()
                .anyMatch(
                        codePoint ->
                                (rejectWhitespace
                                                && (Character.isWhitespace(codePoint)
                                                        || Character.isSpaceChar(codePoint)))
                                        || switch (Character.getType(codePoint)) {
                                            case Character.CONTROL,
                                                    Character.FORMAT,
                                                    Character.SURROGATE,
                                                    Character.PRIVATE_USE,
                                                    Character.UNASSIGNED ->
                                                    true;
                                            default -> false;
                                        });
    }

    private static boolean containsInvalidDisplayCharacter(String value) {
        return value.codePoints()
                .anyMatch(
                        codePoint ->
                                switch (Character.getType(codePoint)) {
                                    case Character.CONTROL, Character.FORMAT, Character.SURROGATE ->
                                            true;
                                    default -> false;
                                });
    }

    private static void requirePresent(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw invalid(field, field + " 값을 입력해 주세요.");
        }
    }

    private static void requirePreNormalizationLimit(String value, String field) {
        if (value.codePointCount(0, value.length()) > PRE_NORMALIZATION_LIMIT) {
            throw invalid(field, field + " 입력이 너무 깁니다.");
        }
    }

    private static InputValidationException invalid(String field, String message) {
        return new InputValidationException(field, message);
    }
}
