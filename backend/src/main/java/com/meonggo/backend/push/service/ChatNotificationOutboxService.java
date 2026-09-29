package com.meonggo.backend.push.service;

import com.meonggo.backend.push.notification.ChatNotificationPublishException;
import com.meonggo.backend.push.notification.ChatNotificationPublisher;
import com.meonggo.backend.push.repository.ChatNotificationOutboxRepository;
import com.meonggo.backend.push.security.PushTokenProtection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class ChatNotificationOutboxService {
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);
    private final ChatNotificationOutboxRepository outbox;
    private final PushTokenProtection protection;
    private final ChatNotificationPublisher publisher;
    private final Clock clock;
    private final Duration leaseDuration;
    private final TransactionTemplate transaction;

    public ChatNotificationOutboxService(
            ChatNotificationOutboxRepository outbox,
            PushTokenProtection protection,
            ChatNotificationPublisher publisher,
            Clock clock,
            Duration leaseDuration,
            PlatformTransactionManager manager) {
        this.outbox = outbox;
        this.protection = protection;
        this.publisher = publisher;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(15);
    }

    public boolean publishNext() {
        Instant now = clock.instant();
        var claim =
                transaction.execute(
                        status -> outbox.claimNext(now, now.plus(leaseDuration)).orElse(null));
        if (claim == null) return false;

        boolean delivered = false;
        boolean transientFailure = false;
        String transientCode = null;
        var devices = outbox.findEligibleDevices(claim.recipientMemberId(), now);
        for (var device : devices) {
            String token;
            try {
                token = protection.decrypt(device.ciphertext());
            } catch (IllegalStateException exception) {
                outbox.clearDevice(device.sessionId(), device.ciphertext());
                continue;
            }
            try {
                publisher.publish(token, claim.notification());
                delivered = true;
            } catch (ChatNotificationPublishException exception) {
                if (exception.permanentTokenFailure()) {
                    outbox.clearDevice(device.sessionId(), device.ciphertext());
                } else {
                    transientFailure = true;
                    transientCode = exception.safeCode();
                }
            } catch (RuntimeException exception) {
                transientFailure = true;
                transientCode = "FCM_ERROR";
            }
        }

        Instant completedAt = clock.instant();
        if (transientFailure) {
            if (claim.attemptCount() >= MAX_ATTEMPTS
                    || !claim.createdAt().plus(MAX_AGE).isAfter(completedAt)) {
                complete(claim, "SKIPPED", "RETRY_EXHAUSTED", completedAt);
            } else {
                Duration backoff = backoff(claim.attemptCount());
                retry(claim, transientCode, completedAt.plus(backoff));
            }
        } else if (delivered) {
            complete(claim, "SENT", null, completedAt);
        } else {
            complete(claim, "SKIPPED", "NO_ACTIVE_DEVICE", completedAt);
        }
        return true;
    }

    private void complete(
            ChatNotificationOutboxRepository.Claim claim,
            String status,
            String code,
            Instant completedAt) {
        transaction.executeWithoutResult(
                transactionStatus -> {
                    if (!outbox.complete(
                            claim.outboxId(), claim.leaseUntil(), status, code, completedAt)) {
                        throw new IllegalStateException("Chat notification lease was lost");
                    }
                });
    }

    private void retry(
            ChatNotificationOutboxRepository.Claim claim, String code, Instant nextAttemptAt) {
        transaction.executeWithoutResult(
                transactionStatus -> {
                    if (!outbox.retry(
                            claim.outboxId(), claim.leaseUntil(), safeCode(code), nextAttemptAt)) {
                        throw new IllegalStateException("Chat notification lease was lost");
                    }
                });
    }

    private static Duration backoff(int attemptCount) {
        long multiplier = 1L << Math.min(attemptCount - 1, 7);
        Duration result = Duration.ofSeconds(30L * multiplier);
        return result.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : result;
    }

    private static String safeCode(String code) {
        return code == null || !code.matches("[A-Z0-9_]{1,50}") ? "FCM_ERROR" : code;
    }
}
