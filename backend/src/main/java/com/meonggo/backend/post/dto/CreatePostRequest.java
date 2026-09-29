package com.meonggo.backend.post.dto;

import com.meonggo.backend.post.entity.AnimalDetails;
import com.meonggo.backend.post.entity.CaseType;
import java.math.BigDecimal;
import java.util.UUID;

/** 원문 크기·형식·정규화·날짜·위치 정책 검증을 완료한 등록 입력이다. */
public record CreatePostRequest(
        UUID clientRequestId,
        CaseType type,
        AnimalDetails details,
        LocationInput eventLocation,
        LocationInput currentLocation) {
    @Override
    public String toString() {
        return "CreatePostRequest[redacted]";
    }

    public record LocationInput(
            String regionCode,
            String emdCode,
            String publicLocation,
            String exactLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean exactLocationVisible,
            String disclosurePolicyVersion) {
        @Override
        public String toString() {
            return "LocationInput[redacted]";
        }
    }
}
