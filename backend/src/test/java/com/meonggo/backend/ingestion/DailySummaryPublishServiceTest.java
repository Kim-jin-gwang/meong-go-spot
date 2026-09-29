package com.meonggo.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.meonggo.backend.ingestion.notification.DailySummaryNotification;
import com.meonggo.backend.ingestion.notification.DailySummaryPublisher;
import com.meonggo.backend.ingestion.repository.DailySummaryPublishRepository;
import com.meonggo.backend.ingestion.service.DailySummaryPublishService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
class DailySummaryPublishServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final String SOURCE = "ANIMAL_PROTECTION_API";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DailySummaryPublishRepository runs;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void setup() {
        jdbc.update("delete from ingestion_run");
    }

    @Test
    void publishesOnlySuccessfulDailyRunIncludingZeroCountWithExactPayload() {
        insert("INITIAL_FULL", "SUCCEEDED", LocalDate.of(2026, 9, 8), 10, 2, null);
        insert("BACKFILL", "SUCCEEDED", LocalDate.of(2026, 9, 9), 10, 2, null);
        insert("DAILY_INCREMENTAL", "FAILED", LocalDate.of(2026, 9, 10), 10, 2, null);
        long target =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 0, 0, null);
        var published = new ArrayList<DailySummaryNotification>();

        assertThat(service(runs, published::add).publishNext()).isTrue();
        assertThat(published)
                .containsExactly(
                        new DailySummaryNotification(target, LocalDate.of(2026, 9, 11), 0, 0));
        assertThat(published.getFirst().data())
                .containsOnly(
                        entry("ingestionRunId", Long.toString(target)),
                        entry("summaryDate", "2026-09-11"),
                        entry("animalCount", "0"),
                        entry("shelterCount", "0"));
        assertThat(publishedAt(target)).isEqualTo(NOW);
        assertThat(service(runs, published::add).publishNext()).isFalse();
    }

    @Test
    void rollsBackFailedSendAndRetriesTheSameRun() {
        long target =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 7, 3, null);
        var failFirst = new AtomicBoolean(true);
        var attempts = new ArrayList<DailySummaryNotification>();
        DailySummaryPublisher publisher =
                notification -> {
                    attempts.add(notification);
                    if (failFirst.getAndSet(false))
                        throw new IllegalStateException("provider-failed");
                };
        var service = service(runs, publisher);

        assertThatThrownBy(service::publishNext).isInstanceOf(IllegalStateException.class);
        assertThat(publishedAt(target)).isNull();
        assertThat(service.publishNext()).isTrue();
        assertThat(attempts).hasSize(2);
        assertThat(publishedAt(target)).isEqualTo(NOW);
    }

    @Test
    void providerAcceptanceBeforeDatabaseFailureMayRetryAtLeastOnce() {
        long target =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 7, 3, null);
        var failFirstMark = new AtomicBoolean(true);
        var repository =
                new DailySummaryPublishRepository(jdbc) {
                    @Override
                    public void markPublished(long ingestionRunId, Instant publishedAt) {
                        if (failFirstMark.getAndSet(false))
                            throw new IllegalStateException("commit-path-failed");
                        super.markPublished(ingestionRunId, publishedAt);
                    }
                };
        var sends = new AtomicInteger();
        var service = service(repository, notification -> sends.incrementAndGet());

        assertThatThrownBy(service::publishNext).isInstanceOf(IllegalStateException.class);
        assertThat(publishedAt(target)).isNull();
        assertThat(service.publishNext()).isTrue();
        assertThat(sends).hasValue(2);
        assertThat(publishedAt(target)).isEqualTo(NOW);
    }

    @Test
    void publishesOnlyLatestSuccessOnceWhenTheSummaryDateIsRepeated() {
        long older =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 3, 1, null);
        long latest =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 8, 4, null);
        var published = new ArrayList<DailySummaryNotification>();
        var service = service(runs, published::add);

        assertThat(service.publishNext()).isTrue();
        assertThat(published)
                .extracting(DailySummaryNotification::ingestionRunId)
                .containsExactly(latest);
        assertThat(service.publishNext()).isFalse();
        assertThat(publishedAt(older)).isNull();
        assertThat(publishedAt(latest)).isEqualTo(NOW);
    }

    @Test
    void concurrentWorkersSendTheClaimedRunOnceOnNormalCompletion() throws Exception {
        long target =
                insert("DAILY_INCREMENTAL", "SUCCEEDED", LocalDate.of(2026, 9, 11), 9, 2, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sends = new AtomicInteger();
        DailySummaryPublisher publisher =
                notification -> {
                    sends.incrementAndGet();
                    entered.countDown();
                    await(release);
                };
        var service = service(runs, publisher);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(service::publishNext);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(service::publishNext);
            assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }

        assertThat(sends).hasValue(1);
        assertThat(publishedAt(target)).isEqualTo(NOW);
    }

    private DailySummaryPublishService service(
            DailySummaryPublishRepository repository, DailySummaryPublisher publisher) {
        return new DailySummaryPublishService(
                repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private long insert(
            String runType,
            String status,
            LocalDate summaryDate,
            int animals,
            int shelters,
            Instant publishedAt) {
        Instant completed =
                NOW.minusSeconds(
                        1000
                                - jdbc.queryForObject(
                                        "select count(*) from ingestion_run", Integer.class));
        return jdbc.queryForObject(
                """
                insert into ingestion_run(
                    source_system,run_type,status,requested_to_date,inserted_count,shelter_count,
                    started_at,completed_at,summary_published_at)
                values(?,?,?,?,?,?,?,?,?) returning id
                """,
                Long.class,
                SOURCE,
                runType,
                status,
                summaryDate,
                animals,
                shelters,
                java.sql.Timestamp.from(completed.minusSeconds(60)),
                java.sql.Timestamp.from(completed),
                publishedAt == null ? null : java.sql.Timestamp.from(publishedAt));
    }

    private Instant publishedAt(long id) {
        java.sql.Timestamp value =
                jdbc.queryForObject(
                        "select summary_published_at from ingestion_run where id=?",
                        java.sql.Timestamp.class,
                        id);
        return value == null ? null : value.toInstant();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test-timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test-interrupted", exception);
        }
    }
}
