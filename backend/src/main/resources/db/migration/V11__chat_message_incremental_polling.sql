CREATE INDEX idx_chat_message_room_after
    ON chat_message (chat_room_id, id ASC);
