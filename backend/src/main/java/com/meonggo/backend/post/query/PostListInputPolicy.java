package com.meonggo.backend.post.query;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.entity.CaseStatus;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.SourceType;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.text.Normalizer;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class PostListInputPolicy {
    public PostListQuery publicQuery(MultiValueMap<String, String> params) {
        validateKeys(
                params,
                Set.of(
                        "type",
                        "species",
                        "sex",
                        "breedName",
                        "regionCode",
                        "color",
                        "source",
                        "sort",
                        "cursor"));
        CaseType type = enumeration(params, "type", CaseType.class);
        if (type == null) throw invalid("type");
        if (params.containsKey("source") && type != CaseType.SHELTERING)
            throw new BusinessException(PostErrorCode.INVALID_SOURCE_FILTER);
        SourceType source = null;
        if (params.containsKey("source")) {
            source =
                    switch (params.getFirst("source")) {
                        case "USER_POST" -> SourceType.USER;
                        case "SHELTER" -> SourceType.PUBLIC;
                        default -> throw invalid("source");
                    };
        }
        // 5자리 시·군·구, 2자리 시·도 전체, 없으면 전국 — 두 목록 모두 같다 (2026-09-17 전엔 SHELTERING 은 5자리 필수였다).
        String region = params.getFirst("regionCode");
        if (region != null && !region.matches("[0-9]{2}|[0-9]{5}")) throw invalid("regionCode");
        PostListSort sort = enumeration(params, "sort", PostListSort.class);
        if (sort == null) sort = PostListSort.LATEST;
        return new PostListQuery(
                type,
                enumeration(params, "species", Species.class),
                enumeration(params, "sex", Sex.class),
                text(params, "breedName"),
                region,
                text(params, "color"),
                source,
                null,
                sort,
                params.getFirst("cursor"));
    }

    public PostListQuery ownQuery(MultiValueMap<String, String> params) {
        validateKeys(params, Set.of("type", "status", "cursor"));
        CaseStatus status = enumeration(params, "status", CaseStatus.class);
        if (status == CaseStatus.DELETED) throw invalid("status");
        return new PostListQuery(
                enumeration(params, "type", CaseType.class),
                null,
                null,
                null,
                null,
                null,
                SourceType.USER,
                status,
                PostListSort.LATEST,
                params.getFirst("cursor"));
    }

    private void validateKeys(MultiValueMap<String, String> params, Set<String> allowed) {
        if (params.entrySet().stream()
                .anyMatch(
                        e ->
                                !allowed.contains(e.getKey())
                                        || e.getValue().size() != 1
                                        || e.getValue().getFirst() == null)) throw invalid("query");
    }

    private <T extends Enum<T>> T enumeration(
            MultiValueMap<String, String> params, String name, Class<T> type) {
        String value = params.getFirst(name);
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw invalid(name);
        }
    }

    private String text(MultiValueMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) > 100
                || value.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || Character.getType(c) == Character.FORMAT
                                                || (c >= 0xD800 && c <= 0xDFFF)))
            throw invalid(name);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > 100) throw invalid(name);
        return normalized.isEmpty() ? null : normalized;
    }

    private InputValidationException invalid(String field) {
        return new InputValidationException(field, "조회 조건을 확인해 주세요.");
    }
}
