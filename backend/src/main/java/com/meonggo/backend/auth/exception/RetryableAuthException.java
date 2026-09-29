package com.meonggo.backend.auth.exception;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.ErrorCode;

public class RetryableAuthException extends BusinessException {
    private final long retryAfterSeconds;

    public RetryableAuthException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
