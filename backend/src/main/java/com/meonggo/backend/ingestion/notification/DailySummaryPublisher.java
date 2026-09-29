package com.meonggo.backend.ingestion.notification;

public interface DailySummaryPublisher {
    void publish(DailySummaryNotification notification);
}
