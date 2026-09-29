package com.meonggo.backend.ingestion.service;

import com.meonggo.backend.ingestion.dto.IngestionResponse;
import com.meonggo.backend.ingestion.dto.IngestionResponse.DataSyncStatus;
import com.meonggo.backend.ingestion.repository.IngestionStatusRepository;
import com.meonggo.backend.ingestion.repository.IngestionStatusRepository.SuccessfulRun;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IngestionStatusService {
    private static final String SOURCE_SYSTEM = "ANIMAL_PROTECTION_API";
    private static final Duration DELAY_THRESHOLD = Duration.ofHours(36);
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final IngestionStatusRepository runs;
    private final Clock clock;
    private final TransactionTemplate reads;

    public IngestionStatusService(
            IngestionStatusRepository runs,
            @Qualifier("dataSourceClock") Clock clock,
            PlatformTransactionManager transactions) {
        this.runs = runs;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
    }

    public IngestionResponse.Status status() {
        return reads.execute(transaction -> readStatus());
    }

    public IngestionResponse.Daily dailySummary() {
        return reads.execute(
                transaction ->
                        new IngestionResponse.Daily(
                                runs.latestDailySummary(SOURCE_SYSTEM)
                                        .map(
                                                run ->
                                                        new IngestionResponse.Summary(
                                                                run.id(),
                                                                run.requestedToDate() == null
                                                                        ? run.completedAt()
                                                                                .atZone(
                                                                                        SERVICE_ZONE)
                                                                                .toLocalDate()
                                                                        : run.requestedToDate(),
                                                                run.insertedCount(),
                                                                run.shelterCount(),
                                                                run.completedAt()))
                                        .orElse(null)));
    }

    private IngestionResponse.Status readStatus() {
        var attempt = runs.latestOperationalAttempt(SOURCE_SYSTEM).orElse(null);
        var success = runs.latestSuccessful(SOURCE_SYSTEM).orElse(null);
        DataSyncStatus status = resolveStatus(attempt, success);
        return new IngestionResponse.Status(
                SOURCE_SYSTEM,
                status,
                success == null ? null : success.completedAt(),
                success == null ? null : success.lastSourceUpdatedAt(),
                attempt == null ? null : attempt.startedAt(),
                success == null ? 0 : success.fetchedCount(),
                success == null ? 0 : success.insertedCount(),
                success == null ? 0 : success.updatedCount(),
                success == null ? 0 : success.shelterCount(),
                success == null ? 0 : success.failedCount());
    }

    private DataSyncStatus resolveStatus(
            IngestionStatusRepository.Attempt attempt, SuccessfulRun success) {
        if (attempt != null && "RUNNING".equals(attempt.status())) return DataSyncStatus.RUNNING;
        if (attempt != null && "FAILED".equals(attempt.status())) return DataSyncStatus.FAILED;
        if (success == null) return DataSyncStatus.NEVER_SYNCED;
        if (success.completedAt().isBefore(clock.instant().minus(DELAY_THRESHOLD))) {
            return DataSyncStatus.DELAYED;
        }
        return DataSyncStatus.SUCCEEDED;
    }
}
