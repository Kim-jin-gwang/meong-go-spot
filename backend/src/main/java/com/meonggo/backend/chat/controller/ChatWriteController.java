package com.meonggo.backend.chat.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.chat.dto.ChatWriteResponse;
import com.meonggo.backend.chat.service.ChatWriteService;
import com.meonggo.backend.chat.web.ChatMessageInput;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.web.PostJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatWriteController {
    private final PostJsonReader reader;
    private final ChatWriteService chats;

    public ChatWriteController(PostJsonReader reader, ChatWriteService chats) {
        this.reader = reader;
        this.chats = chats;
    }

    @PostMapping("/api/v1/posts/{postId}/chat-room")
    public ResponseEntity<ApiResponse<ChatWriteResponse.Room>> createRoom(
            @PathVariable long postId, @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                "채팅방을 준비했습니다.", chats.createRoom(postId, principal.memberId())));
    }

    @PostMapping(
            value = "/api/v1/chat-rooms/{chatRoomId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ChatWriteResponse.Message>> send(
            @PathVariable long chatRoomId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        var input = ChatMessageInput.from(reader.read(request));
        return ResponseEntity.status(201)
                .header("Cache-Control", "no-store")
                .body(
                        ApiResponse.success(
                                "메시지를 전송했습니다.",
                                chats.send(chatRoomId, principal.memberId(), input)));
    }
}
