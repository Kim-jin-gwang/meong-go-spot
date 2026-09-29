package com.meonggo.backend.push.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class ChatNotificationOutboxScheduler {
    private static final Logger LOG =
            LoggerFactory.getLogger(ChatNotificationOutboxScheduler.class);
    private final ChatNotificationOutboxService service;

    public ChatNotificationOutboxScheduler(ChatNotificationOutboxService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fcm.chat.poll-interval:PT5S}")
    public void publishNext() {
        try {
            while (service.publishNext()) {
                // Drain currently due events before waiting for the next scheduled run.
            }
        } catch (RuntimeException exception) {
            LOG.warn(
                    "Chat notification publish deferred: code=FCM-001 exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
