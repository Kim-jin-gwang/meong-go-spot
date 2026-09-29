package com.meonggo.backend.chat.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ChatErrorCode implements ErrorCode {
    PUBLIC_POST(HttpStatus.CONFLICT, "CHAT-001", "사용자 게시물에서만 채팅을 시작할 수 있습니다."),
    OWN_POST(HttpStatus.CONFLICT, "CHAT-002", "본인 게시물에는 채팅을 시작할 수 없습니다."),
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHAT-003", "채팅방을 찾을 수 없습니다."),
    READ_ONLY(HttpStatus.CONFLICT, "CHAT-004", "종료된 게시물의 채팅에는 메시지를 보낼 수 없습니다."),
    INVALID_MESSAGE_POSITION(HttpStatus.BAD_REQUEST, "CHAT-005", "채팅 메시지 위치가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ChatErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
