package com.meonggo.backend.global.error;

import org.springframework.http.HttpStatus;

/** 모든 오류 코드가 구현하는 계약. 공통 오류는 {@link CommonErrorCode}에, 도메인 오류는 각 도메인의 exception 패키지에 둔다. */
public interface ErrorCode {

    HttpStatus status();

    String code();

    String message();
}
