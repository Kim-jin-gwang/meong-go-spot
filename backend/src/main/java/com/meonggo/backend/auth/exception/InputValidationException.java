package com.meonggo.backend.auth.exception;

/** 입력값 없이 필드와 안전한 안내문만 전달한다. */
public class InputValidationException extends RuntimeException {
    private final String field;

    public InputValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
