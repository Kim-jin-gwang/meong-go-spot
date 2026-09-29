package com.meonggo.backend.push.notification;

import java.util.Map;

public record ChatNotification(long chatRoomId, long messageId, long postId) {
    public Map<String, String> data() {
        return Map.of(
                "type", "CHAT_MESSAGE",
                "chatRoomId", Long.toString(chatRoomId),
                "messageId", Long.toString(messageId),
                "postId", Long.toString(postId));
    }
}
