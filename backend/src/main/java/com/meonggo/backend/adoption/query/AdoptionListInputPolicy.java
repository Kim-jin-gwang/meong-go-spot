package com.meonggo.backend.adoption.query;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

@Component
public class AdoptionListInputPolicy {
    private static final Set<String> ALLOWED = Set.of("regionCode", "species", "sex", "cursor");

    public AdoptionListQuery parse(MultiValueMap<String, String> parameters) {
        if (parameters.entrySet().stream()
                .anyMatch(
                        entry ->
                                !ALLOWED.contains(entry.getKey())
                                        || entry.getValue().size() != 1
                                        || entry.getValue().getFirst() == null)) {
            throw invalid("query");
        }

        // P1·D3 와 같은 지역 범위 — 5자리 시·군·구, 2자리 시·도 전체, 없으면 전국 (2026-09-22: 그 전엔 5자리 필수).
        String regionCode = parameters.getFirst("regionCode");
        if (regionCode != null && !regionCode.matches("[0-9]{2}|[0-9]{5}")) {
            throw invalid("regionCode");
        }

        Species species = species(parameters.getFirst("species"));
        Sex sex = sex(parameters.getFirst("sex"));
        return new AdoptionListQuery(regionCode, species, sex, parameters.getFirst("cursor"));
    }

    private Species species(String value) {
        if (value == null) return null;
        return switch (value) {
            case "DOG" -> Species.DOG;
            case "CAT" -> Species.CAT;
            default -> throw invalid("species");
        };
    }

    /** UNKNOWN 은 받지 않는다 — 고를 수 있는 값이 아니라 원천에 성별이 없다는 뜻이다 (api-spec AD1). */
    private Sex sex(String value) {
        if (value == null) return null;
        return switch (value) {
            case "MALE" -> Sex.MALE;
            case "FEMALE" -> Sex.FEMALE;
            default -> throw invalid("sex");
        };
    }

    private InputValidationException invalid(String field) {
        return new InputValidationException(field, "조회 조건을 확인해 주세요.");
    }
}
