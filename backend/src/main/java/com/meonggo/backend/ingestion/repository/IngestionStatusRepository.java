package com.meonggo.backend.ingestion.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionStatusRepository {
    private final JdbcTemplate jdbc;

    public IngestionStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Attempt> latestOperationalAttempt(String sourceSystem) {
        return jdbc
                .query(
                        """
                        select status,started_at
                        from ingestion_run
                        where source_system=?
                          and run_type=(
                              case when exists(
                                  select 1 from ingestion_run
                                  where source_system=? and run_type='DAILY_INCREMENTAL'
                              ) then 'DAILY_INCREMENTAL' else 'INITIAL_FULL' end
                          )
                        order by started_at desc,id desc
                        limit 1
                        """,
                        (row, index) ->
                                new Attempt(
                                        row.getString("status"),
                                        row.getTimestamp("started_at").toInstant()),
                        sourceSystem,
                        sourceSystem)
                .stream()
                .findFirst();
    }

    public Optional<SuccessfulRun> latestSuccessful(String sourceSystem) {
        return jdbc
                .query(
                        """
                        select completed_at,last_source_updated_at,
                               fetched_count,inserted_count,updated_count,shelter_count,failed_count
                        from ingestion_run
                        where source_system=? and status='SUCCEEDED'
                          and run_type=(
                              case when exists(
                                  select 1 from ingestion_run
                                  where source_system=? and run_type='DAILY_INCREMENTAL'
                                    and status='SUCCEEDED'
                              ) then 'DAILY_INCREMENTAL' else 'INITIAL_FULL' end
                          )
                        order by completed_at desc,id desc
                        limit 1
                        """,
                        (row, index) ->
                                new SuccessfulRun(
                                        row.getTimestamp("completed_at").toInstant(),
                                        instant(row.getTimestamp("last_source_updated_at")),
                                        row.getInt("fetched_count"),
                                        row.getInt("inserted_count"),
                                        row.getInt("updated_count"),
                                        row.getInt("shelter_count"),
                                        row.getInt("failed_count")),
                        sourceSystem,
                        sourceSystem)
                .stream()
                .findFirst();
    }

    public Optional<DailyRun> latestDailySummary(String sourceSystem) {
        return jdbc
                .query(
                        """
                        select id,requested_to_date,inserted_count,shelter_count,completed_at
                        from ingestion_run
                        where source_system=? and run_type='DAILY_INCREMENTAL'
                          and status='SUCCEEDED'
                        order by completed_at desc,id desc
                        limit 1
                        """,
                        (row, index) ->
                                new DailyRun(
                                        row.getLong("id"),
                                        row.getObject("requested_to_date", LocalDate.class),
                                        row.getInt("inserted_count"),
                                        row.getInt("shelter_count"),
                                        row.getTimestamp("completed_at").toInstant()),
                        sourceSystem)
                .stream()
                .findFirst();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Attempt(String status, Instant startedAt) {}

    public record SuccessfulRun(
            Instant completedAt,
            Instant lastSourceUpdatedAt,
            int fetchedCount,
            int insertedCount,
            int updatedCount,
            int shelterCount,
            int failedCount) {}

    public record DailyRun(
            long id,
            LocalDate requestedToDate,
            int insertedCount,
            int shelterCount,
            Instant completedAt) {}
}
