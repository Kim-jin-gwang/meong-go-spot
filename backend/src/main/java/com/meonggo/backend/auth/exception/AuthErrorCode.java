package com.meonggo.backend.auth.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuthErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-001", "아이디 또는 비밀번호가 올바르지 않습니다."),
    INVALID_SESSION(HttpStatus.UNAUTHORIZED, "AUTH-003", "로그인 세션이 만료되었거나 유효하지 않습니다."),
    LOGIN_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH-004", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    ACCOUNT_UNAVAILABLE(HttpStatus.FORBIDDEN, "AUTH-005", "사용할 수 없는 계정입니다."),
    AUTH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTH-006", "인증 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH-002", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH-007", "접근 권한이 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    AuthErrorCode(HttpStatus status, String code, String message) {
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
