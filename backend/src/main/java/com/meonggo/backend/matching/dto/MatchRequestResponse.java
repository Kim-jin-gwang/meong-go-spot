package com.meonggo.backend.matching.dto;

import java.time.Instant;

public record MatchRequestResponse(
        long postId, long matchRunId, String status, Instant createdAt) {}
