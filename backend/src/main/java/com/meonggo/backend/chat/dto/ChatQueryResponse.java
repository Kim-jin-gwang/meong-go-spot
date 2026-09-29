package com.meonggo.backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

public final class ChatQueryResponse {
    private ChatQueryResponse() {}

    public record Rooms(List<RoomItem> items, Page page) {
        public Rooms {
            items = List.copyOf(items);
        }
    }

    public record Messages(
            long chatRoomId,
            Post post,
            Member otherMember,
            boolean readOnly,
            Long myLastReadMessageId,
            Long otherLastReadMessageId,
            List<Message> items,
            Page page,
            int pollAfterMs) {
        public Messages {
            items = List.copyOf(items);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoomItem(
            long chatRoomId,
            Post post,
            Member otherMember,
            LastMessage lastMessage,
            long unreadCount,
            boolean hasUnread,
            boolean readOnly) {}

    /**
     * 대화가 걸린 게시물입니다.
     *
     * <p>이름·품종은 등록할 때 비워 둘 수 있어 없을 수 있다. 화면은 있는 것만 이어 붙여 "콩이 · 말티즈 수컷" 처럼 한 줄로 부른다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Post(
            long postId,
            String type,
            String status,
            String name,
            String species,
            String breedName,
            String sex,
            String thumbnailUrl) {}

    public record Member(long memberId, String nickname) {}

    public record LastMessage(long messageId, String content, Instant createdAt) {
        @Override
        public String toString() {
            return "LastMessage[redacted]";
        }
    }

    public record Message(long messageId, Member sender, String content, Instant createdAt) {
        @Override
        public String toString() {
            return "Message[redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(int size, boolean hasNext, String nextCursor, Long nextAfterMessageId) {}
}
