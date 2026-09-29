package com.meonggo.backend.post.query;

import com.meonggo.backend.post.entity.CaseStatus;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.SourceType;
import com.meonggo.backend.post.entity.Species;

public record PostListQuery(
        CaseType type,
        Species species,
        Sex sex,
        String breedName,
        String regionCode,
        String color,
        SourceType source,
        CaseStatus status,
        PostListSort sort,
        String cursor) {
    @Override
    public String toString() {
        return "PostListQuery[redacted]";
    }
}
