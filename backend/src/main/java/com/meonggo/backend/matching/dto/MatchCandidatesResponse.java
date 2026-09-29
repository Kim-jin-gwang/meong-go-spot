package com.meonggo.backend.matching.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchCandidatesResponse(
        long postId,
        String analysisStatus,
        LatestRun latestRun,
        Long resultRunId,
        boolean usingPreviousResult,
        Integer recommendedPollAfterMs,
        List<Candidate> candidates) {
    public MatchCandidatesResponse {
        candidates = List.copyOf(candidates);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LatestRun(
            long matchRunId,
            String status,
            Integer candidateCount,
            String errorCode,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt) {}

    public record Candidate(int rank, PostSummary post) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostSummary(
            long postId,
            String type,
            String source,
            String status,
            String name,
            String species,
            String breedName,
            String sex,
            String color,
            LocalDate eventDate,
            String publicLocation,
            String thumbnailUrl,
            Author author,
            Shelter shelter) {}

    public record Author(String nickname) {}

    public record Shelter(String name, @JsonInclude(JsonInclude.Include.ALWAYS) String phone) {}
}
