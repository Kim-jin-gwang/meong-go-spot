package com.meonggo.backend.push.notification;

import com.google.api.core.ApiFuture;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class FirebaseChatNotificationPublisher implements ChatNotificationPublisher {
    private final FirebaseMessaging messaging;
    private final Duration timeout;

    public FirebaseChatNotificationPublisher(FirebaseMessaging messaging, Duration timeout) {
        this.messaging = messaging;
        this.timeout = timeout;
    }

    @Override
    public void publish(String registrationToken, ChatNotification notification) {
        Message message =
                Message.builder()
                        .setToken(registrationToken)
                        .putAllData(notification.data())
                        .build();
        ApiFuture<String> request = messaging.sendAsync(message);
        try {
            request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            request.cancel(true);
            throw transientFailure("INTERRUPTED");
        } catch (TimeoutException exception) {
            request.cancel(true);
            throw transientFailure("TIMEOUT");
        } catch (ExecutionException exception) {
            request.cancel(true);
            throw classify(exception.getCause());
        }
    }

    private static ChatNotificationPublishException classify(Throwable cause) {
        if (cause instanceof FirebaseMessagingException firebase) {
            MessagingErrorCode code = firebase.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED
                    || code == MessagingErrorCode.INVALID_ARGUMENT
                    || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
                return new ChatNotificationPublishException(true, "PERMANENT_TOKEN");
            }
            return transientFailure(code == null ? "FCM_ERROR" : code.name());
        }
        return transientFailure("FCM_ERROR");
    }

    private static ChatNotificationPublishException transientFailure(String code) {
        return new ChatNotificationPublishException(false, code);
    }
}
