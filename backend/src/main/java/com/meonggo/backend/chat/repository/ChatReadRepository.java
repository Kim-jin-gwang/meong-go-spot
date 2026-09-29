package com.meonggo.backend.chat.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatReadRepository {
    private final JdbcTemplate jdbc;

    public ChatReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean messageBelongsToRoom(long roomId, long messageId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from chat_message where id=? and chat_room_id=?)",
                        Boolean.class,
                        messageId,
                        roomId));
    }

    public void updatePosition(long roomId, boolean owner, long messageId) {
        String column = owner ? "owner_last_read_message_id" : "requester_last_read_message_id";
        jdbc.update("update chat_room set " + column + "=? where id=?", messageId, roomId);
    }
}
