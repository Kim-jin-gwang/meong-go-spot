package com.meonggo.backend.post.service;

import com.meonggo.backend.auth.exception.InputValidationException;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class PostMutationInput {
    private PostMutationInput() {}

    public static long version(JsonNode root) {
        if (root == null || !root.isObject()) throw invalid("payload");
        var value = root.get("version");
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0) throw invalid("version");
        return value.asLong();
    }

    public static void fields(JsonNode root, Set<String> allowed) {
        if (root == null
                || !root.isObject()
                || root.propertyNames().stream().anyMatch(name -> !allowed.contains(name)))
            throw invalid("payload");
    }

    public static InputValidationException invalid(String field) {
        return new InputValidationException(field, "입력 형식과 필수값을 확인해 주세요.");
    }
}
