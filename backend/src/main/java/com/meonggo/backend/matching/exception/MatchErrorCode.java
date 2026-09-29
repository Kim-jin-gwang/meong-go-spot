package com.meonggo.backend.matching.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MatchErrorCode implements ErrorCode {
    INELIGIBLE_POST(HttpStatus.CONFLICT, "MATCH-001", "매칭 분석을 요청할 수 없는 게시물입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    MatchErrorCode(HttpStatus status, String code, String message) {
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
