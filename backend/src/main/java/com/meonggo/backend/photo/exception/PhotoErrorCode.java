package com.meonggo.backend.photo.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum PhotoErrorCode implements ErrorCode {
    INVALID_COUNT(HttpStatus.BAD_REQUEST, "PHOTO-001", "사진은 1장 이상 10장 이하로 등록해야 합니다."),
    UNSUPPORTED_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PHOTO-002", "지원하지 않는 사진 형식입니다."),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "PHOTO-003", "처리할 수 없는 사진입니다."),
    TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "PHOTO-004", "사진 용량이 허용 범위를 초과했습니다."),
    INVALID_DIMENSIONS(HttpStatus.UNPROCESSABLE_CONTENT, "PHOTO-005", "사진 해상도가 허용 범위를 벗어났습니다."),
    STORAGE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "PHOTO-006", "사진 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "PHOTO-007", "사진을 찾을 수 없습니다."),
    UPLOAD_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "PHOTO-008", "사진 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    PhotoErrorCode(HttpStatus status, String code, String message) {
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
