package com.meonggo.backend.chat.repository;

import com.meonggo.backend.chat.dto.ChatWriteResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatWriteRepository {
    private final JdbcTemplate jdbc;

    public ChatWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PostTarget> findPost(long postId, boolean lock) {
        return jdbc
                .query(
                        """
            select c.id,c.source_type,c.status,u.member_id,m.nickname
            from animal_case c
            left join user_post u on u.animal_case_id=c.id and c.source_type='USER'
            left join member m on m.id=u.member_id
            where c.id=? and c.deleted_at is null and c.status<>'DELETED'
              and (c.source_type='PUBLIC'
                   or (m.status='ACTIVE' and m.deleted_at is null))
            """
                                + (lock ? " for update of c" : ""),
                        (row, index) ->
                                new PostTarget(
                                        row.getLong("id"),
                                        row.getString("source_type"),
                                        row.getString("status"),
                                        row.getObject("member_id", Long.class),
                                        row.getString("nickname")),
                        postId)
                .stream()
                .findFirst();
    }

    public void lockMembers(long firstId, long secondId) {
        jdbc.queryForList(
                "select id from member where id in (?,?) order by id for update",
                Long.class,
                Math.min(firstId, secondId),
                Math.max(firstId, secondId));
    }

    public void lockCase(long postId) {
        jdbc.queryForList("select id from animal_case where id=? for update", Long.class, postId);
    }

    public Optional<ChatWriteResponse.Room> findRoom(long postId, long requesterId) {
        return jdbc
                .query(
                        """
            select r.id,r.animal_case_id,r.last_message_at,r.created_at,
                   r.owner_member_id,m.nickname
            from chat_room r
            join member m on m.id=r.owner_member_id
            where r.animal_case_id=? and r.requester_member_id=?
            """,
                        (row, index) -> room(row),
                        postId,
                        requesterId)
                .stream()
                .findFirst();
    }

    public ChatWriteResponse.Room insertRoom(
            long postId, long ownerId, long requesterId, String ownerNickname) {
        return jdbc.queryForObject(
                """
            insert into chat_room(
                animal_case_id,owner_member_id,requester_member_id,created_at,updated_at)
            values(?,?,?,clock_timestamp(),clock_timestamp())
            returning id,animal_case_id,last_message_at,created_at,owner_member_id
            """,
                (row, index) ->
                        new ChatWriteResponse.Room(
                                row.getLong("id"),
                                row.getLong("animal_case_id"),
                                new ChatWriteResponse.Member(
                                        row.getLong("owner_member_id"), ownerNickname),
                                null,
                                row.getTimestamp("created_at").toInstant()),
                postId,
                ownerId,
                requesterId);
    }

    public Optional<StoredMessage> findMessage(long senderId, UUID clientMessageId) {
        return jdbc
                .query(
                        """
            select cm.id,cm.chat_room_id,cm.request_hash,cm.content,cm.created_at,
                   cm.sender_member_id,m.nickname
            from chat_message cm join member m on m.id=cm.sender_member_id
            where cm.sender_member_id=? and cm.client_message_id=?
            """,
                        (row, index) ->
                                new StoredMessage(
                                        row.getLong("chat_room_id"),
                                        row.getString("request_hash"),
                                        new ChatWriteResponse.Message(
                                                row.getLong("id"),
                                                row.getLong("chat_room_id"),
                                                new ChatWriteResponse.Member(
                                                        row.getLong("sender_member_id"),
                                                        row.getString("nickname")),
                                                row.getString("content"),
                                                row.getTimestamp("created_at").toInstant())),
                        senderId,
                        clientMessageId)
                .stream()
                .findFirst();
    }

    public ChatWriteResponse.Message insertMessage(
            long roomId, long senderId, UUID clientMessageId, String requestHash, String content) {
        Instant createdAt =
                jdbc.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
                        .toInstant();
        long messageId =
                jdbc.queryForObject(
                        """
            insert into chat_message(
                chat_room_id,sender_member_id,client_message_id,request_hash,content,created_at)
            values(?,?,?,?,?,?) returning id
            """,
                        Long.class,
                        roomId,
                        senderId,
                        clientMessageId,
                        requestHash,
                        content,
                        java.sql.Timestamp.from(createdAt));
        String nickname =
                jdbc.queryForObject(
                        "select nickname from member where id=?", String.class, senderId);
        return new ChatWriteResponse.Message(
                messageId,
                roomId,
                new ChatWriteResponse.Member(senderId, nickname),
                content,
                createdAt);
    }

    public void touchRoom(long roomId, Instant createdAt) {
        jdbc.update(
                "update chat_room set last_message_at=?,updated_at=? where id=?",
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt),
                roomId);
    }

    public void insertNotification(long messageId, long recipientMemberId, Instant createdAt) {
        jdbc.update(
                """
                insert into chat_notification_outbox(
                    message_id,recipient_member_id,next_attempt_at,created_at)
                values(?,?,?,?)
                """,
                messageId,
                recipientMemberId,
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));
    }

    private ChatWriteResponse.Room room(java.sql.ResultSet row) throws java.sql.SQLException {
        var last = row.getTimestamp("last_message_at");
        return new ChatWriteResponse.Room(
                row.getLong("id"),
                row.getLong("animal_case_id"),
                new ChatWriteResponse.Member(
                        row.getLong("owner_member_id"), row.getString("nickname")),
                last == null ? null : last.toInstant(),
                row.getTimestamp("created_at").toInstant());
    }

    public record PostTarget(
            long postId, String source, String status, Long ownerId, String ownerNickname) {}

    public record StoredMessage(
            long roomId, String requestHash, ChatWriteResponse.Message response) {}
}
