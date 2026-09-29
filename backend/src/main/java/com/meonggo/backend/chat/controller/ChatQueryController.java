package com.meonggo.backend.chat.controller;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Messages;
import com.meonggo.backend.chat.dto.ChatQueryResponse.Rooms;
import com.meonggo.backend.chat.service.ChatQueryService;
import com.meonggo.backend.global.common.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat-rooms")
public class ChatQueryController {
    private final ChatQueryService queries;

    public ChatQueryController(ChatQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public ApiResponse<Rooms> rooms(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.success(
                "채팅방 목록을 조회했습니다.", queries.rooms(principal.memberId(), parameters));
    }

    @GetMapping("/{chatRoomId}/messages")
    public ApiResponse<Messages> messages(
            @PathVariable long chatRoomId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.success(
                "메시지를 조회했습니다.", queries.messages(chatRoomId, principal.memberId(), parameters));
    }
}
