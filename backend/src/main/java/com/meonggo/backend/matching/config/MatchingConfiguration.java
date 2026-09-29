package com.meonggo.backend.matching.config;

import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class MatchingConfiguration {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private final String modelId;
    private final String modelVersion;

    public MatchingConfiguration(Environment environment) {
        modelId = identifier(environment.getRequiredProperty("matching.model-id"), 100, "model-id");
        modelVersion =
                identifier(
                        environment.getRequiredProperty("matching.model-version"),
                        50,
                        "model-version");
    }

    public String modelId() {
        return modelId;
    }

    public String modelVersion() {
        return modelVersion;
    }

    private static String identifier(String value, int maxLength, String name) {
        if (value.length() > maxLength || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalStateException("Invalid matching " + name);
        }
        return value;
    }
}
