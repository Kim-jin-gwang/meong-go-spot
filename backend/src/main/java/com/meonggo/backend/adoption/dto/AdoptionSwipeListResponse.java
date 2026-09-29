package com.meonggo.backend.adoption.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse.Availability;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * AD6 넘긴 동물 목록.
 *
 * <p>찜 목록과 필드가 거의 같지만 {@code favorited} 가 더 있다. 히스토리에서 바로 찜을 해제하므로 넘김 기록과 현재 찜 상태를 한 번에 받아야 한다
 * (api-spec AD6).
 */
public record AdoptionSwipeListResponse(LocalDate asOfDate, List<Item> items, Page page) {
    public AdoptionSwipeListResponse {
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
            Long daysSinceNoticeEnd,
            Instant lastSyncedAt,
            Instant swipedAt,
            boolean favorited,
            Availability availability) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(int size, boolean hasNext, String nextCursor) {}
}
