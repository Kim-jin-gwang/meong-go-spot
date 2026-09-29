package com.meonggo.backend.member.service;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MemberRelationalDataErasureService {
    private static final Logger LOG =
            LoggerFactory.getLogger(MemberRelationalDataErasureService.class);
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int BATCH_SIZE = 100;
    private static final String DUE_MEMBER_IDS =
            """
            select id
            from member
            where status='WITHDRAWN'
              and deleted_at<=?
              and (personal_data_erased_at is null or relational_data_erased_at is null)
            order by deleted_at,id
            limit ?
            """;
    private static final String IS_READY =
            """
            select exists(
                select 1
                from member
                where id=?
                  and status='WITHDRAWN'
                  and deleted_at<=?
                  and personal_data_erased_at is not null
                  and relational_data_erased_at is null
            )
            """;
    private static final String DELETE_OWNED_CASES =
            """
            delete from animal_case
            where id in (
                select animal_case_id
                from user_post
                where member_id=?
            )
            """;
    private static final String ENQUEUE_PHOTO_ERASURE =
            """
            insert into member_photo_erasure_task(
                storage_uri,deadline_at,next_attempt_at,created_at,updated_at)
            select photo.storage_uri,member.deleted_at + interval '30 days',?,?,?
            from animal_photo photo
            join user_post post on post.animal_case_id=photo.animal_case_id
            join member on member.id=post.member_id
            where post.member_id=? and photo.storage_type='USER_UPLOAD'
            on conflict (storage_uri) do nothing
            """;
    private static final String MARK_RELATIONAL_DATA_ERASED =
            """
            update member
            set relational_data_erased_at=?, updated_at=?
            where id=?
              and status='WITHDRAWN'
              and deleted_at<=?
              and personal_data_erased_at is not null
              and relational_data_erased_at is null
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MemberRelationalDataErasureService(
            JdbcTemplate jdbc,
            @Qualifier("authClock") Clock clock,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(30);
    }

    public void eraseDueMembers() {
        Instant erasedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = erasedAt.minus(RETENTION);
        List<Long> memberIds =
                jdbc.queryForList(DUE_MEMBER_IDS, Long.class, Timestamp.from(cutoff), BATCH_SIZE);
        for (long memberId : memberIds) {
            try {
                transaction.executeWithoutResult(status -> eraseMember(memberId, cutoff, erasedAt));
            } catch (RuntimeException exception) {
                LOG.warn(
                        "Member data erasure deferred: phase=relational memberId={} code=DATABASE_ERROR",
                        memberId);
            }
        }
    }

    private void eraseMember(long memberId, Instant cutoff, Instant erasedAt) {
        boolean ready =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                IS_READY, Boolean.class, memberId, Timestamp.from(cutoff)));
        if (!ready) return;

        jdbc.update("delete from chat_message where sender_member_id=?", memberId);
        jdbc.update(
                "delete from chat_room where owner_member_id=? or requester_member_id=?",
                memberId,
                memberId);
        jdbc.update("delete from auth_session where member_id=?", memberId);
        jdbc.update("delete from adoption_favorite where member_id=?", memberId);
        jdbc.update("delete from adoption_swipe where member_id=?", memberId);
        Timestamp now = Timestamp.from(erasedAt);
        jdbc.update(ENQUEUE_PHOTO_ERASURE, now, now, now, memberId);
        jdbc.update(DELETE_OWNED_CASES, memberId);
        jdbc.update(MARK_RELATIONAL_DATA_ERASED, now, now, memberId, Timestamp.from(cutoff));
    }
}
