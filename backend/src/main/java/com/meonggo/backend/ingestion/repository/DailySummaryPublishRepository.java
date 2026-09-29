package com.meonggo.backend.ingestion.repository;

import com.meonggo.backend.ingestion.notification.DailySummaryNotification;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DailySummaryPublishRepository {
    private static final String SOURCE_SYSTEM = "ANIMAL_PROTECTION_API";

    private final JdbcTemplate jdbc;

    public DailySummaryPublishRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DailySummaryNotification> lockNextUnpublished() {
        return jdbc
                .query(
                        """
                        select run.id,
                               coalesce(
                                   run.requested_to_date,
                                   (run.completed_at at time zone 'Asia/Seoul')::date
                               ) as summary_date,
                               run.inserted_count,run.shelter_count
                        from ingestion_run run
                        where run.source_system=?
                          and run.run_type='DAILY_INCREMENTAL'
                          and run.status='SUCCEEDED'
                          and run.summary_published_at is null
                          and not exists (
                              select 1
                              from ingestion_run published
                              where published.source_system=run.source_system
                                and published.run_type='DAILY_INCREMENTAL'
                                and published.status='SUCCEEDED'
                                and published.summary_published_at is not null
                                and coalesce(
                                    published.requested_to_date,
                                    (published.completed_at at time zone 'Asia/Seoul')::date
                                )=coalesce(
                                    run.requested_to_date,
                                    (run.completed_at at time zone 'Asia/Seoul')::date
                                )
                          )
                          and not exists (
                              select 1
                              from ingestion_run newer
                              where newer.source_system=run.source_system
                                and newer.run_type='DAILY_INCREMENTAL'
                                and newer.status='SUCCEEDED'
                                and coalesce(
                                    newer.requested_to_date,
                                    (newer.completed_at at time zone 'Asia/Seoul')::date
                                )=coalesce(
                                    run.requested_to_date,
                                    (run.completed_at at time zone 'Asia/Seoul')::date
                                )
                                and (newer.completed_at,newer.id)>(run.completed_at,run.id)
                          )
                        order by summary_date,run.completed_at,run.id
                        for update of run skip locked
                        limit 1
                        """,
                        (row, index) ->
                                new DailySummaryNotification(
                                        row.getLong("id"),
                                        row.getObject("summary_date", LocalDate.class),
                                        row.getInt("inserted_count"),
                                        row.getInt("shelter_count")),
                        SOURCE_SYSTEM)
                .stream()
                .findFirst();
    }

    public void markPublished(long ingestionRunId, Instant publishedAt) {
        int updated =
                jdbc.update(
                        """
                        update ingestion_run
                        set summary_published_at=?
                        where id=? and summary_published_at is null
                        """,
                        Timestamp.from(publishedAt),
                        ingestionRunId);
        if (updated != 1) throw new IllegalStateException("Daily summary claim was lost");
    }
}
