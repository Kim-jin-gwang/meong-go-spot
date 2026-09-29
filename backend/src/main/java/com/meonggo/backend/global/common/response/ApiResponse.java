package com.meonggo.backend.global.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.meonggo.backend.global.error.ErrorCode;

/**
 * 모든 일반 JSON 응답을 감싸는 공통 래퍼.
 *
 * <p>{@code data}가 null이면 JSON에서 생략된다. 성공 응답은 Controller가, 오류 응답은 GlobalExceptionHandler가 만든다
 * (backend/docs/backend-common-settings.md).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data) {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "요청에 성공했습니다.";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(SUCCESS_CODE, message, data);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, T data) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), data);
    }
}
