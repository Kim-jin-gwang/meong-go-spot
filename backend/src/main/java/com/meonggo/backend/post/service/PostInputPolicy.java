package com.meonggo.backend.post.service;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.dto.CreatePostRequest;
import com.meonggo.backend.post.dto.CreatePostRequest.LocationInput;
import com.meonggo.backend.post.entity.AnimalDetails;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.location.RegionCodeCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PostInputPolicy {
    private static final String POLICY = "exact-location-v1";
    private static final Set<String> FIELDS =
            Set.of(
                    "clientRequestId",
                    "type",
                    "name",
                    "species",
                    "breedName",
                    "sex",
                    "color",
                    "eventDate",
                    "eventTime",
                    "eventLocation",
                    "currentLocation",
                    "featureText");
    private static final Set<String> LOCATION_FIELDS =
            Set.of(
                    "regionCode",
                    "emdCode",
                    "exactLocation",
                    "latitude",
                    "longitude",
                    "exactLocationVisible",
                    "disclosurePolicyVersion");
    private final RegionCodeCatalog regions;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PostInputPolicy(RegionCodeCatalog regions) {
        this(regions, Clock.systemUTC());
    }

    // 명시적 Clock으로 한국 날짜 경계 검증을 시스템 기본 시간대와 분리한다.
    public PostInputPolicy(RegionCodeCatalog regions, Clock clock) {
        this.regions = regions;
        this.clock = clock;
    }

    public CreatePostRequest validate(JsonNode root) {
        fields(root, FIELDS, "payload");
        String requestId = string(root, "clientRequestId", true);
        if (!requestId.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw invalid("clientRequestId");
        CaseType type = enumeration(root, "type", CaseType.class);
        LocationInput event = location(root.get("eventLocation"), "eventLocation");
        LocationInput current = null;
        if (type == CaseType.LOST && root.has("currentLocation")) throw invalid("currentLocation");
        if (type == CaseType.SHELTERING)
            current = location(root.get("currentLocation"), "currentLocation");
        var details =
                new AnimalDetails(
                        text(root, "name", 50),
                        enumeration(root, "species", Species.class),
                        text(root, "breedName", 100),
                        enumeration(root, "sex", Sex.class),
                        text(root, "color", 100),
                        date(root),
                        time(root),
                        text(root, "featureText", 2000));
        return new CreatePostRequest(UUID.fromString(requestId), type, details, event, current);
    }

    private LocationInput location(JsonNode node, String field) {
        fields(node, LOCATION_FIELDS, field);
        String region = string(node, "regionCode", true);
        String emd = string(node, "emdCode", false);
        String display;
        try {
            display = regions.resolve(region, emd);
        } catch (InputValidationException ex) {
            throw invalid(field + "." + ex.field());
        }
        String exact = text(node, "exactLocation", 200);
        JsonNode visibleNode = node.get("exactLocationVisible");
        if (visibleNode == null || !visibleNode.isBoolean())
            throw invalid(field + ".exactLocationVisible");
        boolean visible = visibleNode.asBoolean();
        String policy = string(node, "disclosurePolicyVersion", false);
        if ((visible && !POLICY.equals(policy)) || (policy != null && !POLICY.equals(policy)))
            throw new BusinessException(PostErrorCode.DISCLOSURE_REQUIRED);
        if (visible && exact == null) throw invalid(field + ".exactLocation");
        if (!visible && policy != null) throw invalid(field + ".disclosurePolicyVersion");
        BigDecimal latitude = coordinate(node, "latitude", 90, field);
        BigDecimal longitude = coordinate(node, "longitude", 180, field);
        if ((latitude == null) != (longitude == null)) throw invalid(field);
        return new LocationInput(region, emd, display, exact, latitude, longitude, visible, policy);
    }

    BigDecimal coordinate(JsonNode node, String name, int limit, String field) {
        var value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalid(field + "." + name);
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value.asString());
        } catch (NumberFormatException ex) {
            throw invalid(field + "." + name);
        }
        if (decimal.abs().compareTo(BigDecimal.valueOf(limit)) > 0)
            throw invalid(field + "." + name);
        // 지수가 극단적으로 작은 입력에서 거대한 10의 거듭제곱을 만들지 않는다.
        if (decimal.abs().compareTo(new BigDecimal("0.0000005")) < 0)
            return BigDecimal.ZERO.setScale(6);
        // PostgreSQL numeric(9,6)과 비교 hash가 같은 좌표를 표현한다.
        return decimal.setScale(6, RoundingMode.HALF_UP);
    }

    LocalDate date(JsonNode root) {
        String value = string(root, "eventDate", true);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("eventDate");
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid("eventDate");
        }
        if (date.getYear() < 1) throw invalid("eventDate");
        if (date.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))
            throw new InputValidationException("eventDate", "사건 날짜는 오늘 이후일 수 없습니다.");
        return date;
    }

    LocalTime time(JsonNode root) {
        String value = string(root, "eventTime", false);
        if (value == null) return null;
        if (!value.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}")) throw invalid("eventTime");
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalid("eventTime");
        }
    }

    <T extends Enum<T>> T enumeration(JsonNode root, String field, Class<T> type) {
        try {
            return Enum.valueOf(type, string(root, field, true));
        } catch (IllegalArgumentException ex) {
            throw invalid(field);
        }
    }

    String text(JsonNode root, String field, int limit) {
        String value = string(root, field, false);
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) > limit) throw invalid(field);
        if (value.codePoints()
                .anyMatch(
                        c ->
                                Character.isISOControl(c)
                                        || Character.getType(c) == Character.FORMAT
                                        || (c >= 0xD800 && c <= 0xDFFF))) throw invalid(field);
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
        if (normalized.codePointCount(0, normalized.length()) > limit) throw invalid(field);
        return normalized.isEmpty() ? null : normalized;
    }

    String string(JsonNode root, String field, boolean required) {
        var value = root.get(field);
        if (value == null || value.isNull()) {
            if (required) throw invalid(field);
            return null;
        }
        if (!value.isString()) throw invalid(field);
        return value.asString();
    }

    void fields(JsonNode node, Set<String> allowed, String field) {
        if (node == null || !node.isObject()) throw invalid(field);
        if (node.propertyNames().stream().anyMatch(name -> !allowed.contains(name)))
            throw invalid(field);
    }

    InputValidationException invalid(String field) {
        return new InputValidationException(field, "입력 형식과 필수값을 확인해 주세요.");
    }
}
