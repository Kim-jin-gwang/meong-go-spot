package com.meonggo.backend.chat.dto;

import java.time.Instant;

public final class ChatWriteResponse {
    private ChatWriteResponse() {}

    public record Member(long memberId, String nickname) {}

    public record Room(
            long chatRoomId,
            long postId,
            Member otherMember,
            Instant lastMessageAt,
            Instant createdAt) {}

    public record Message(
            long messageId, long chatRoomId, Member sender, String content, Instant createdAt) {
        @Override
        public String toString() {
            return "Message[redacted]";
        }
    }
}
