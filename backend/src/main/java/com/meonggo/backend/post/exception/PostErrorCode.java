package com.meonggo.backend.post.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PostErrorCode implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "POST-001", "게시물을 찾을 수 없습니다."),
    NOT_OWNER(HttpStatus.FORBIDDEN, "POST-002", "이 게시물에 대한 요청 권한이 없습니다."),
    NOT_ACTIVE(HttpStatus.CONFLICT, "POST-003", "현재 상태에서는 요청한 작업을 수행할 수 없습니다."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "POST-004", "게시물이 변경되었습니다. 최신 내용을 다시 확인해 주세요."),
    PUBLIC_IMMUTABLE(HttpStatus.FORBIDDEN, "POST-005", "공공 보호동물 정보는 사용자가 변경할 수 없습니다."),
    INVALID_SOURCE_FILTER(HttpStatus.BAD_REQUEST, "POST-006", "게시물 유형과 출처 필터 조합이 올바르지 않습니다."),
    INVALID_CURSOR(HttpStatus.BAD_REQUEST, "CURSOR-001", "목록 커서가 유효하지 않습니다."),
    DISCLOSURE_REQUIRED(HttpStatus.CONFLICT, "POST-007", "최신 공개 동의 내용을 확인해 주세요."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "IDEMPOTENCY-001", "같은 요청 식별자가 다른 요청 내용에 사용되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PostErrorCode(HttpStatus status, String code, String message) {
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
