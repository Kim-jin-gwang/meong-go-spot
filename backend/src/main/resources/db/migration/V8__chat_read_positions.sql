ALTER TABLE chat_room
    ADD COLUMN owner_last_read_message_id bigint,
    ADD COLUMN requester_last_read_message_id bigint,
    ADD CONSTRAINT ck_chat_room_owner_read_position
        CHECK (owner_last_read_message_id IS NULL OR owner_last_read_message_id > 0),
    ADD CONSTRAINT ck_chat_room_requester_read_position
        CHECK (requester_last_read_message_id IS NULL OR requester_last_read_message_id > 0);

CREATE INDEX idx_chat_message_room_sender_id
    ON chat_message (chat_room_id, sender_member_id, id);
