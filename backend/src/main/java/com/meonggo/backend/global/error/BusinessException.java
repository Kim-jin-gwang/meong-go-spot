package com.meonggo.backend.global.error;

/** 비즈니스 규칙 위반. Service에서 발생시키고 GlobalExceptionHandler가 ErrorCode의 상태·코드로 공통 오류 응답을 만든다. */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
