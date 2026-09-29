package com.meonggo.backend.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PushDeviceRepository {
    private final JdbcTemplate jdbc;

    public PushDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockRegistrationKeys(UUID installationId, String lookupHash) {
        String first = installationId.toString();
        String second = lookupHash;
        if (first.compareTo(second) > 0) {
            String swap = first;
            first = second;
            second = swap;
        }
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, first);
        jdbc.queryForObject(
                "select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, second);
    }

    public boolean ownsActiveSession(long sessionId, long memberId, Instant now) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        select exists(
                            select 1 from auth_session s join member m on m.id=s.member_id
                            where s.id=? and s.member_id=? and s.revoked_at is null
                              and s.expires_at>? and m.status='ACTIVE' and m.deleted_at is null)
                        """,
                        Boolean.class,
                        sessionId,
                        memberId,
                        Timestamp.from(now)));
    }

    public void reassign(
            long sessionId,
            UUID installationId,
            String platform,
            String ciphertext,
            String lookupHash,
            Instant now) {
        jdbc.update(
                """
                update auth_session
                set push_installation_id=null,push_platform=null,push_token_ciphertext=null,
                    push_token_lookup_hash=null,push_last_seen_at=null
                where id<>? and (push_installation_id=? or push_token_lookup_hash=?)
                """,
                sessionId,
                installationId,
                lookupHash);
        int updated =
                jdbc.update(
                        """
                        update auth_session
                        set push_installation_id=?,push_platform=?,push_token_ciphertext=?,
                            push_token_lookup_hash=?,push_last_seen_at=?
                        where id=?
                        """,
                        installationId,
                        platform,
                        ciphertext,
                        lookupHash,
                        Timestamp.from(now),
                        sessionId);
        if (updated != 1) throw new IllegalStateException("Push session update was lost");
    }

    public void clear(long sessionId, long memberId, UUID installationId) {
        jdbc.update(
                """
                update auth_session
                set push_installation_id=null,push_platform=null,push_token_ciphertext=null,
                    push_token_lookup_hash=null,push_last_seen_at=null
                where id=? and member_id=? and push_installation_id=?
                """,
                sessionId,
                memberId,
                installationId);
    }
}
