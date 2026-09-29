package com.meonggo.backend.chat.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.chat.dto.ChatReadResponse;
import com.meonggo.backend.chat.service.ChatReadService;
import com.meonggo.backend.chat.web.ChatReadInput;
import com.meonggo.backend.global.common.response.ApiResponse;
import com.meonggo.backend.post.web.PostJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat-rooms")
public class ChatReadController {
    private final PostJsonReader reader;
    private final ChatReadService reads;

    public ChatReadController(PostJsonReader reader, ChatReadService reads) {
        this.reader = reader;
        this.reads = reads;
    }

    @PutMapping(value = "/{chatRoomId}/read", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatReadResponse> update(
            @PathVariable long chatRoomId,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletRequest request) {
        var input = ChatReadInput.from(reader.read(request));
        return ApiResponse.success(
                "채팅 읽음 위치를 갱신했습니다.",
                reads.update(chatRoomId, principal.memberId(), input.lastReadMessageId()));
    }
}
