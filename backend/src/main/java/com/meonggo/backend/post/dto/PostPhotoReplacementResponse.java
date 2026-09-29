package com.meonggo.backend.post.dto;

import java.time.Instant;
import java.util.List;

public record PostPhotoReplacementResponse(
        long postId, long version, List<Photo> photos, Instant updatedAt) {
    public PostPhotoReplacementResponse {
        photos = List.copyOf(photos);
    }

    public record Photo(long photoId, String url, int sortOrder) {}
}
