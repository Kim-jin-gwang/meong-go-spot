package com.meonggo.backend.adoption.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AdoptionErrorCode implements ErrorCode {
    CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ADOPTION-001", "입양 후보를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AdoptionErrorCode(HttpStatus status, String code, String message) {
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
