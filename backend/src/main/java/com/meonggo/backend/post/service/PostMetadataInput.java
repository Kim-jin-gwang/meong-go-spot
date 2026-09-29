package com.meonggo.backend.post.service;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.entity.AnimalDetails;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.location.LocationProtection;
import com.meonggo.backend.post.location.RegionCodeCatalog;
import com.meonggo.backend.post.repository.PostMetadataRepository.Location;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class PostMetadataInput {
    private static final String POLICY = "exact-location-v1";
    private static final Set<String> FIELDS =
            Set.of(
                    "version",
                    "name",
                    "species",
                    "breedName",
                    "sex",
                    "color",
                    "eventDate",
                    "eventTime",
                    "featureText",
                    "eventLocation",
                    "currentLocation");
    private static final Set<String> LOCATION_FIELDS =
            Set.of(
                    "regionCode",
                    "emdCode",
                    "exactLocation",
                    "latitude",
                    "longitude",
                    "exactLocationVisible",
                    "disclosurePolicyVersion");
    private final PostInputPolicy policy;
    private final RegionCodeCatalog regions;
    private final LocationProtection protection;

    public PostMetadataInput(
            PostInputPolicy policy, RegionCodeCatalog regions, LocationProtection protection) {
        this.policy = policy;
        this.regions = regions;
        this.protection = protection;
    }

    public void validateFields(JsonNode patch) {
        policy.fields(patch, FIELDS, "payload");
    }

    public Merge merge(
            JsonNode patch,
            CaseType type,
            AnimalDetails stored,
            List<Location> locations,
            Instant now) {
        if (type == CaseType.LOST && patch.has("currentLocation"))
            throw policy.invalid("currentLocation");
        Set<String> roles = type == CaseType.LOST ? Set.of("EVENT") : Set.of("EVENT", "CURRENT");
        if (locations.size() != roles.size()
                || !locations.stream()
                        .map(Location::role)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(roles)) throw policy.invalid("eventLocation");
        var details =
                new AnimalDetails(
                        patch.has("name") ? policy.text(patch, "name", 50) : stored.name(),
                        patch.has("species")
                                ? policy.enumeration(patch, "species", Species.class)
                                : stored.species(),
                        patch.has("breedName")
                                ? policy.text(patch, "breedName", 100)
                                : stored.breedName(),
                        patch.has("sex")
                                ? policy.enumeration(patch, "sex", Sex.class)
                                : stored.sex(),
                        patch.has("color") ? policy.text(patch, "color", 100) : stored.color(),
                        patch.has("eventDate") ? policy.date(patch) : stored.eventDate(),
                        patch.has("eventTime") ? policy.time(patch) : stored.eventTime(),
                        patch.has("featureText")
                                ? policy.text(patch, "featureText", 2000)
                                : stored.featureText());
        boolean contentChanged = !details.equals(stored);
        List<Location> changed = new ArrayList<>();
        for (var location : locations) {
            String field = location.role().equals("EVENT") ? "eventLocation" : "currentLocation";
            var merged = mergeLocation(patch.get(field), location, field, now);
            if (!merged.location().equals(location)) changed.add(merged.location());
            contentChanged |= merged.contentChanged();
        }
        return new Merge(details, List.copyOf(changed), contentChanged);
    }

    private LocationMerge mergeLocation(
            JsonNode patch, Location stored, String field, Instant now) {
        if (patch != null) policy.fields(patch, LOCATION_FIELDS, field);
        String region =
                has(patch, "regionCode")
                        ? policy.string(patch, "regionCode", true)
                        : stored.region();
        String emd = has(patch, "emdCode") ? policy.string(patch, "emdCode", false) : stored.emd();
        String display = stored.display();
        if (has(patch, "regionCode") || has(patch, "emdCode")) {
            try {
                display = regions.resolve(region, emd);
            } catch (InputValidationException exception) {
                throw policy.invalid(field + "." + exception.field());
            }
        }
        boolean visible = stored.visible();
        if (has(patch, "exactLocationVisible")) {
            if (!patch.get("exactLocationVisible").isBoolean())
                throw policy.invalid(field + ".exactLocationVisible");
            visible = patch.get("exactLocationVisible").asBoolean();
        }
        boolean confirms = has(patch, "disclosurePolicyVersion");
        if (confirms
                && (!patch.get("disclosurePolicyVersion").isString()
                        || !POLICY.equals(patch.get("disclosurePolicyVersion").asString())))
            throw new BusinessException(PostErrorCode.DISCLOSURE_REQUIRED);
        String ciphertext = stored.ciphertext();
        boolean exactChanged = false;
        if (has(patch, "exactLocation") || visible) {
            String oldExact = ciphertext == null ? null : protection.decrypt(ciphertext);
            String exact =
                    has(patch, "exactLocation")
                            ? policy.text(patch, "exactLocation", 200)
                            : oldExact;
            if (visible && (exact == null || exact.isBlank()))
                throw policy.invalid(field + ".exactLocation");
            exactChanged = !Objects.equals(oldExact, exact);
            if (exactChanged) ciphertext = exact == null ? null : protection.encrypt(exact);
        }
        if (visible && (!stored.visible() || exactChanged) && !confirms)
            throw new BusinessException(PostErrorCode.DISCLOSURE_REQUIRED);
        BigDecimal latitude =
                has(patch, "latitude")
                        ? policy.coordinate(patch, "latitude", 90, field)
                        : stored.latitude();
        BigDecimal longitude =
                has(patch, "longitude")
                        ? policy.coordinate(patch, "longitude", 180, field)
                        : stored.longitude();
        if ((latitude == null) != (longitude == null)
                || latitude != null && latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude != null && longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0)
            throw policy.invalid(field);
        // Numeric scale and freshly generated encryption nonces are not content differences.
        boolean contentChanged =
                !Objects.equals(region, stored.region())
                        || !Objects.equals(emd, stored.emd())
                        || !Objects.equals(display, stored.display())
                        || exactChanged
                        || !sameCoordinate(latitude, stored.latitude())
                        || !sameCoordinate(longitude, stored.longitude());
        boolean confirmPublic = visible && confirms;
        return new LocationMerge(
                new Location(
                        stored.role(),
                        region,
                        emd,
                        display,
                        ciphertext,
                        sameCoordinate(latitude, stored.latitude()) ? stored.latitude() : latitude,
                        sameCoordinate(longitude, stored.longitude())
                                ? stored.longitude()
                                : longitude,
                        visible,
                        confirmPublic ? POLICY : stored.policy(),
                        confirmPublic ? now : stored.consentedAt()),
                contentChanged);
    }

    private static boolean has(JsonNode node, String field) {
        return node != null && node.has(field);
    }

    private static boolean sameCoordinate(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private record LocationMerge(Location location, boolean contentChanged) {
        @Override
        public String toString() {
            return "LocationMerge[redacted]";
        }
    }

    public record Merge(
            AnimalDetails details, List<Location> changedLocations, boolean contentChanged) {
        public boolean changed() {
            return contentChanged || !changedLocations.isEmpty();
        }

        @Override
        public String toString() {
            return "Merge[redacted]";
        }
    }
}
