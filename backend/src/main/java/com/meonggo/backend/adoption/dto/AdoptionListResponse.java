package com.meonggo.backend.adoption.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdoptionListResponse(LocalDate asOfDate, List<Item> items, Page page) {
    public AdoptionListResponse {
        items = List.copyOf(items);
    }

    public record Item(
            long postId,
            Species species,
            String breedName,
            Sex sex,
            String color,
            String publicLocation,
            String thumbnailUrl,
            LocalDate noticeEndDate,
            long daysSinceNoticeEnd,
            Instant lastSyncedAt,
            boolean favorited) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(int size, boolean hasNext, String nextCursor) {}
}
