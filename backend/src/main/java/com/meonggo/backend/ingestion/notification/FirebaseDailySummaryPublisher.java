package com.meonggo.backend.ingestion.notification;

import com.google.api.core.ApiFuture;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FirebaseDailySummaryPublisher implements DailySummaryPublisher {
    static final String TOPIC = "daily-intake-summary";

    private final FirebaseMessaging messaging;
    private final Duration timeout;

    public FirebaseDailySummaryPublisher(FirebaseMessaging messaging, Duration timeout) {
        this.messaging = messaging;
        this.timeout = timeout;
    }

    @Override
    public void publish(DailySummaryNotification notification) {
        Message message = Message.builder().setTopic(TOPIC).putAllData(notification.data()).build();
        ApiFuture<String> request = messaging.sendAsync(message);
        try {
            request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            request.cancel(true);
            throw new DailySummaryPublishException(exception);
        } catch (ExecutionException | TimeoutException exception) {
            request.cancel(true);
            throw new DailySummaryPublishException(exception);
        }
    }
}
