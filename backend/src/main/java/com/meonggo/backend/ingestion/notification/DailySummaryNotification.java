package com.meonggo.backend.ingestion.notification;

import java.time.LocalDate;
import java.util.Map;

public record DailySummaryNotification(
        long ingestionRunId, LocalDate summaryDate, int animalCount, int shelterCount) {
    public Map<String, String> data() {
        return Map.of(
                "ingestionRunId", Long.toString(ingestionRunId),
                "summaryDate", summaryDate.toString(),
                "animalCount", Integer.toString(animalCount),
                "shelterCount", Integer.toString(shelterCount));
    }
}
