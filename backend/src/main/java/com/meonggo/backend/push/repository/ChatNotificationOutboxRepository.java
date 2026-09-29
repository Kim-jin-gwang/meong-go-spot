package com.meonggo.backend.push.repository;

import com.meonggo.backend.push.notification.ChatNotification;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatNotificationOutboxRepository {
    private final JdbcTemplate jdbc;

    public ChatNotificationOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Claim> claimNext(Instant now, Instant leaseUntil) {
        return jdbc
                .query(
                        """
                        with candidate as (
                            select id from chat_notification_outbox
                            where (status='PENDING' and next_attempt_at<=?)
                               or (status='PROCESSING' and lease_until<=?)
                            order by next_attempt_at,id
                            for update skip locked
                            limit 1
                        )
                        update chat_notification_outbox outbox
                        set status='PROCESSING',attempt_count=attempt_count+1,
                            lease_until=?,last_error_code=null
                        from candidate,chat_message message,chat_room room
                        where outbox.id=candidate.id and message.id=outbox.message_id
                          and room.id=message.chat_room_id
                        returning outbox.id,outbox.recipient_member_id,outbox.attempt_count,
                                  outbox.created_at,outbox.lease_until,message.id message_id,
                                  room.id chat_room_id,room.animal_case_id
                        """,
                        (row, index) ->
                                new Claim(
                                        row.getLong("id"),
                                        row.getLong("recipient_member_id"),
                                        row.getInt("attempt_count"),
                                        row.getTimestamp("created_at").toInstant(),
                                        row.getTimestamp("lease_until").toInstant(),
                                        new ChatNotification(
                                                row.getLong("chat_room_id"),
                                                row.getLong("message_id"),
                                                row.getLong("animal_case_id"))),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(leaseUntil))
                .stream()
                .findFirst();
    }

    public List<Device> findEligibleDevices(long memberId, Instant now) {
        return jdbc.query(
                """
                select session.id,session.push_token_ciphertext
                from auth_session session join member on member.id=session.member_id
                where session.member_id=? and session.revoked_at is null
                  and session.expires_at>? and session.push_installation_id is not null
                  and session.push_platform='ANDROID'
                  and session.push_token_ciphertext is not null
                  and session.push_token_lookup_hash is not null
                  and member.status='ACTIVE' and member.deleted_at is null
                order by session.id
                """,
                (row, index) ->
                        new Device(row.getLong("id"), row.getString("push_token_ciphertext")),
                memberId,
                Timestamp.from(now));
    }

    public void clearDevice(long sessionId, String ciphertext) {
        jdbc.update(
                """
                update auth_session
                set push_installation_id=null,push_platform=null,push_token_ciphertext=null,
                    push_token_lookup_hash=null,push_last_seen_at=null
                where id=? and push_token_ciphertext=?
                """,
                sessionId,
                ciphertext);
    }

    public boolean complete(
            long outboxId, Instant leaseUntil, String status, String code, Instant now) {
        return jdbc.update(
                        """
                        update chat_notification_outbox
                        set status=?,lease_until=null,last_error_code=?,completed_at=?
                        where id=? and status='PROCESSING' and lease_until=?
                        """,
                        status,
                        code,
                        Timestamp.from(now),
                        outboxId,
                        Timestamp.from(leaseUntil))
                == 1;
    }

    public boolean retry(long outboxId, Instant leaseUntil, String code, Instant nextAttemptAt) {
        return jdbc.update(
                        """
                        update chat_notification_outbox
                        set status='PENDING',lease_until=null,last_error_code=?,
                            next_attempt_at=?,completed_at=null
                        where id=? and status='PROCESSING' and lease_until=?
                        """,
                        code,
                        Timestamp.from(nextAttemptAt),
                        outboxId,
                        Timestamp.from(leaseUntil))
                == 1;
    }

    public record Claim(
            long outboxId,
            long recipientMemberId,
            int attemptCount,
            Instant createdAt,
            Instant leaseUntil,
            ChatNotification notification) {}

    public record Device(long sessionId, String ciphertext) {}
}
