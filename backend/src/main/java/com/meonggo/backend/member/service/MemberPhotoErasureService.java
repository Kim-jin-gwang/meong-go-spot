package com.meonggo.backend.member.service;

import com.meonggo.backend.photo.storage.PhotoStorage;
import com.meonggo.backend.photo.storage.PhotoStoragePaths;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MemberPhotoErasureService {
    private static final Logger LOG = LoggerFactory.getLogger(MemberPhotoErasureService.class);
    private static final int BATCH_SIZE = 100;
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(24);
    private static final String CLAIM_TASKS =
            """
            with candidates as (
                select id
                from member_photo_erasure_task
                where next_attempt_at<=?
                  and (lease_until is null or lease_until<=?)
                order by next_attempt_at,id
                limit ?
                for update skip locked
            )
            update member_photo_erasure_task task
            set attempt_count=task.attempt_count+1,
                lease_until=?,
                updated_at=?
            from candidates
            where task.id=candidates.id
            returning task.id,task.storage_uri,task.deadline_at,task.attempt_count
            """;

    private final JdbcTemplate jdbc;
    private final PhotoStorage storage;
    private final Clock clock;

    public MemberPhotoErasureService(
            JdbcTemplate jdbc, PhotoStorage storage, @Qualifier("authClock") Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    public void eraseDuePhotos() {
        Instant claimedAt = now();
        List<Task> tasks = claim(claimedAt);
        for (Task task : tasks) erase(task);
    }

    private List<Task> claim(Instant claimedAt) {
        Timestamp now = Timestamp.from(claimedAt);
        return jdbc.query(
                CLAIM_TASKS,
                (result, rowNumber) ->
                        new Task(
                                result.getLong("id"),
                                result.getString("storage_uri"),
                                result.getTimestamp("deadline_at").toInstant(),
                                result.getInt("attempt_count")),
                now,
                now,
                BATCH_SIZE,
                Timestamp.from(claimedAt.plus(LEASE)),
                now);
    }

    private void erase(Task task) {
        try {
            if (!PhotoStoragePaths.file(task.storageUri()))
                throw new IllegalStateException("Invalid member photo erasure path");
            storage.delete(task.storageUri());
            jdbc.update("delete from member_photo_erasure_task where id=?", task.id());
        } catch (RuntimeException exception) {
            Instant failedAt = now();
            defer(task, failedAt);
            LOG.warn(
                    "Member photo erasure deferred: taskId={} code=PHOTO-006 overdue={}",
                    task.id(),
                    !task.deadlineAt().isAfter(failedAt));
        }
    }

    private void defer(Task task, Instant failedAt) {
        try {
            jdbc.update(
                    """
                    update member_photo_erasure_task
                    set next_attempt_at=?, lease_until=null, last_error_code='PHOTO-006', updated_at=?
                    where id=?
                    """,
                    Timestamp.from(failedAt.plus(retryDelay(task.attemptCount()))),
                    Timestamp.from(failedAt),
                    task.id());
        } catch (RuntimeException exception) {
            LOG.warn(
                    "Member photo erasure state update deferred: taskId={} code=DATABASE_ERROR",
                    task.id());
        }
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 11);
        Duration delay = Duration.ofMinutes(1L << exponent);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Task(long id, String storageUri, Instant deadlineAt, int attemptCount) {}
}
