package com.meonggo.backend.ingestion.notification;

public final class DailySummaryPublishException extends RuntimeException {
    public DailySummaryPublishException(Throwable cause) {
        super("Daily summary provider request failed", cause);
    }
}
