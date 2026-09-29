package com.meonggo.backend.post.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meonggo.backend.post.entity.CaseStatus;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PostListResponse<T>(List<T> items, Page page) {
    public PostListResponse {
        items = List.copyOf(items);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(int size, boolean hasNext, String nextCursor) {}

    public record PublicSummary(
            long postId,
            CaseType type,
            String source,
            String name,
            Species species,
            String breedName,
            Sex sex,
            String color,
            LocalDate eventDate,
            Instant listedAt,
            String publicLocation,
            String thumbnailUrl) {}

    public record OwnSummary(
            long postId,
            CaseType type,
            String source,
            CaseStatus status,
            long version,
            String name,
            Species species,
            String breedName,
            Sex sex,
            String color,
            LocalDate eventDate,
            Instant listedAt,
            String publicLocation,
            String thumbnailUrl,
            Instant updatedAt) {}
}
