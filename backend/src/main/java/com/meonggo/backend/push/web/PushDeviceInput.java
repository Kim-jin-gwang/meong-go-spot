package com.meonggo.backend.push.web;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record PushDeviceInput(String platform, String token) {
    private static final Set<String> FIELDS = Set.of("platform", "token");

    public static PushDeviceInput from(JsonNode node) {
        if (node == null
                || !node.isObject()
                || node.size() != 2
                || !Set.copyOf(node.propertyNames()).equals(FIELDS)) return invalid();
        JsonNode platform = node.get("platform");
        JsonNode token = node.get("token");
        if (platform == null
                || !platform.isString()
                || !"ANDROID".equals(platform.asString())
                || token == null
                || !token.isString()
                || token.asString().isBlank()
                || token.asString().length() > 4096) return invalid();
        return new PushDeviceInput(platform.asString(), token.asString());
    }

    private static <T> T invalid() {
        throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
}
