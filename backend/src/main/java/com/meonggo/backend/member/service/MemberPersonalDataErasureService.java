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
public class MemberPersonalDataErasureService {
    private static final Logger LOG =
            LoggerFactory.getLogger(MemberPersonalDataErasureService.class);
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
    private static final String ERASE_PERSONAL_DATA =
            """
            update member
            set login_id='withdrawn:' || id,
                password_hash='erased:' || id,
                nickname='탈퇴한 회원',
                phone_ciphertext=null,
                phone_lookup_hash=null,
                phone_verified_at=null,
                privacy_collection_agreed=null,
                privacy_collection_policy_version=null,
                privacy_collection_consented_at=null,
                personal_data_erased_at=?,
                updated_at=?
            where id=?
              and status='WITHDRAWN'
              and deleted_at<=?
              and personal_data_erased_at is null
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MemberPersonalDataErasureService(
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
                        "Member data erasure deferred: phase=personal memberId={} code=DATABASE_ERROR",
                        memberId);
            }
        }
    }

    private void eraseMember(long memberId, Instant cutoff, Instant erasedAt) {
        jdbc.update(
                ERASE_PERSONAL_DATA,
                Timestamp.from(erasedAt),
                Timestamp.from(erasedAt),
                memberId,
                Timestamp.from(cutoff));
    }
}
