package com.meonggo.backend.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class DailySummaryPublishScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DailySummaryPublishScheduler.class);

    private final DailySummaryPublishService service;

    public DailySummaryPublishScheduler(DailySummaryPublishService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fcm.daily-summary.poll-interval:PT1M}")
    public void publishNext() {
        try {
            service.publishNext();
        } catch (RuntimeException exception) {
            LOG.warn(
                    "Daily summary publish deferred: code=FCM-001 exceptionType={} rootCauseType={}",
                    exception.getClass().getSimpleName(),
                    rootCauseType(exception),
                    sanitizedStackTrace(exception));
        }
    }

    private static String rootCauseType(RuntimeException exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    private static RuntimeException sanitizedStackTrace(RuntimeException exception) {
        var sanitized = new RuntimeException("Daily summary publish failed; details redacted");
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }
}
