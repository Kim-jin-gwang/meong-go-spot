package com.meonggo.backend.push.notification;

public interface ChatNotificationPublisher {
    void publish(String registrationToken, ChatNotification notification);
}
