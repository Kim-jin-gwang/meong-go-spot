package com.meonggo.backend.post.web;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 변경 JSON은 원문 크기를 먼저 제한하고 중복 key·후행 값을 거부한다. */
@Component
public class PostJsonReader {
    private static final int MAX_BYTES = 65536;
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .build();

    public JsonNode read(HttpServletRequest request) {
        if (request.getHeader("Content-Encoding") != null
                || request.getContentLengthLong() > MAX_BYTES)
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new BusinessException(CommonErrorCode.MALFORMED_JSON);
        }
        if (bytes.length > MAX_BYTES) throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        try {
            return mapper.readTree(bytes);
        } catch (RuntimeException ex) {
            throw new BusinessException(CommonErrorCode.MALFORMED_JSON);
        }
    }
}
