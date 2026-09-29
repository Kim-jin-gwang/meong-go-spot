package com.meonggo.backend.chat.web;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record ChatReadInput(long lastReadMessageId) {
    private static final Set<String> FIELDS = Set.of("lastReadMessageId");

    public ChatReadInput {
        if (lastReadMessageId < 1) invalid();
    }

    public static ChatReadInput from(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 1) return invalid();
        if (!Set.copyOf(node.propertyNames()).equals(FIELDS)) return invalid();
        JsonNode id = node.get("lastReadMessageId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) return invalid();
        return new ChatReadInput(id.asLong());
    }

    private static <T> T invalid() {
        throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
