package com.meonggo.backend.chat.web;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.text.Normalizer;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ChatMessageInput(UUID clientMessageId, String content) {
    private static final Set<String> FIELDS = Set.of("clientMessageId", "content");
    private static final int MAX_CODE_POINTS = 1000;

    public ChatMessageInput {
        if (clientMessageId == null || content == null) invalid();
    }

    public static ChatMessageInput from(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 2) return invalid();
        var names = Set.copyOf(node.propertyNames());
        if (!names.equals(FIELDS)) return invalid();
        JsonNode idNode = node.get("clientMessageId");
        JsonNode contentNode = node.get("content");
        if (!idNode.isString() || !contentNode.isString()) return invalid();
        String rawId = idNode.asString();
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            return invalid();
        }
        if (!id.toString().equalsIgnoreCase(rawId)) return invalid();
        String raw = contentNode.asString();
        if (raw.codePointCount(0, raw.length()) > MAX_CODE_POINTS || containsForbidden(raw)) {
            return invalid();
        }
        String normalized = stripUnicodeWhitespace(Normalizer.normalize(raw, Normalizer.Form.NFC));
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_CODE_POINTS) return invalid();
        return new ChatMessageInput(id, normalized);
    }

    private static boolean containsForbidden(String value) {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.SURROGATE) return true;
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static <T> T invalid() {
        throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    @Override
    public String toString() {
        return "ChatMessageInput[redacted]";
    }
}
