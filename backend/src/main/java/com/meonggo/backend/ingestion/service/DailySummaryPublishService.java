package com.meonggo.backend.ingestion.service;

import com.meonggo.backend.ingestion.notification.DailySummaryPublisher;
import com.meonggo.backend.ingestion.repository.DailySummaryPublishRepository;
import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class DailySummaryPublishService {
    private final DailySummaryPublishRepository runs;
    private final DailySummaryPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public DailySummaryPublishService(
            DailySummaryPublishRepository runs,
            DailySummaryPublisher publisher,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.runs = runs;
        this.publisher = publisher;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    public boolean publishNext() {
        return Boolean.TRUE.equals(
                transaction.execute(
                        status ->
                                runs.lockNextUnpublished()
                                        .map(
                                                notification -> {
                                                    publisher.publish(notification);
                                                    runs.markPublished(
                                                            notification.ingestionRunId(),
                                                            clock.instant());
                                                    return true;
                                                })
                                        .orElse(false)));
    }
}
