package com.meonggo.backend.ping.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Ping 도메인 오류 — 오류 응답 구현 방식의 참고 예제 (backend/docs/backend-ping-guide.md). */
public enum PingErrorCode implements ErrorCode {
    BUSINESS_ERROR(HttpStatus.CONFLICT, "PING-001", "의도적으로 발생시킨 Ping 비즈니스 예외입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PingErrorCode(HttpStatus status, String code, String message) {
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
