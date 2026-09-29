package com.meonggo.backend.auth.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PhoneErrorCode implements ErrorCode {
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "PHONE-001", "인증 요청 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_VERIFICATION(HttpStatus.BAD_REQUEST, "PHONE-002", "휴대전화 인증 정보가 유효하지 않거나 만료되었습니다."),
    PHONE_IN_USE(HttpStatus.CONFLICT, "PHONE-003", "이미 사용 중인 휴대전화 번호입니다. 문의가 필요하면 고객센터로 연락해 주세요."),
    SMS_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "PHONE-004", "인증 문자를 전송할 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PhoneErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
