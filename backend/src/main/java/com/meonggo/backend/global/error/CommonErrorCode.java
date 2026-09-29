package com.meonggo.backend.global.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인에 속하지 않는 공통 오류만 둔다. 이미 사용한 코드는 의미를 바꾸거나 재사용하지 않는다 (backend/docs/backend-common-settings.md).
 */
public enum CommonErrorCode implements ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-001", "요청 값이 올바르지 않습니다."),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "COMMON-002", "요청 본문을 읽을 수 없습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON-003", "필수 파라미터가 누락되었습니다."),
    TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "COMMON-004", "파라미터 타입이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-404", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-405", "지원하지 않는 HTTP 메서드입니다."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "COMMON-406", "제공할 수 없는 응답 미디어 타입입니다."),
    MEDIA_TYPE_NOT_SUPPORTED(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, "COMMON-415", "지원하지 않는 요청 미디어 타입입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-500", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    CommonErrorCode(HttpStatus status, String code, String message) {
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
