package com.meonggo.backend.chat.repository;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ChatAccessRepository {
    private final JdbcTemplate jdbc;

    public ChatAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireActiveViewer(long memberId) {
        var statuses =
                jdbc.queryForList(
                        "select status from member where id=? and deleted_at is null",
                        String.class,
                        memberId);
        if (statuses.size() != 1 || !"ACTIVE".equals(statuses.getFirst())) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_UNAVAILABLE);
        }
    }

    public Optional<Room> findRoom(long roomId, long viewerId, boolean lock) {
        if (lock && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Chat room lock requires a transaction");
        }
        return jdbc
                .query(
                        """
            select r.id,r.animal_case_id,c.case_type,c.status,c.deleted_at,
                   r.owner_member_id,r.requester_member_id,
                   r.owner_last_read_message_id,r.requester_last_read_message_id,
                   om.status owner_status,om.deleted_at owner_deleted_at,
                   rm.status requester_status,rm.deleted_at requester_deleted_at
            from chat_room r
            join animal_case c on c.id=r.animal_case_id and c.source_type='USER'
            join user_post u on u.animal_case_id=c.id and u.member_id=r.owner_member_id
            join member om on om.id=r.owner_member_id
            join member rm on rm.id=r.requester_member_id
            where r.id=? and ? in (r.owner_member_id,r.requester_member_id)
            """
                                + (lock ? " for update of r" : ""),
                        (row, index) ->
                                new Room(
                                        row.getLong("id"),
                                        row.getLong("animal_case_id"),
                                        row.getString("case_type"),
                                        row.getString("status"),
                                        row.getLong("owner_member_id"),
                                        row.getLong("requester_member_id"),
                                        row.getObject("owner_last_read_message_id", Long.class),
                                        row.getObject("requester_last_read_message_id", Long.class),
                                        row.getObject("deleted_at") != null
                                                || !"ACTIVE".equals(row.getString("status"))
                                                || !"ACTIVE".equals(row.getString("owner_status"))
                                                || row.getObject("owner_deleted_at") != null
                                                || !"ACTIVE"
                                                        .equals(row.getString("requester_status"))
                                                || row.getObject("requester_deleted_at") != null),
                        roomId,
                        viewerId)
                .stream()
                .findFirst();
    }

    public record Room(
            long roomId,
            long postId,
            String postType,
            String postStatus,
            long ownerId,
            long requesterId,
            Long ownerLastReadMessageId,
            Long requesterLastReadMessageId,
            boolean readOnly) {}
}
