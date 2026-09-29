package com.meonggo.backend.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;

public final class IngestionResponse {
    private IngestionResponse() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(
            String sourceSystem,
            DataSyncStatus status,
            Instant lastSuccessfulAt,
            Instant lastSourceUpdatedAt,
            Instant lastAttemptAt,
            int fetchedCount,
            int insertedCount,
            int updatedCount,
            int shelterCount,
            int failedCount) {}

    public record Daily(@JsonInclude(JsonInclude.Include.ALWAYS) Summary summary) {}

    public record Summary(
            long ingestionRunId,
            LocalDate summaryDate,
            int animalCount,
            int shelterCount,
            Instant completedAt) {}

    public enum DataSyncStatus {
        RUNNING,
        SUCCEEDED,
        FAILED,
        DELAYED,
        NEVER_SYNCED
    }
}
