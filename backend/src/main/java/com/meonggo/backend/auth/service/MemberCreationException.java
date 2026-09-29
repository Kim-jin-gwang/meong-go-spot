package com.meonggo.backend.auth.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.ErrorCode;

/** 트랜잭션 종료 결과만 전달한다. SQL 원문과 바인딩 값은 원인 예외로 보관하지 않는다. */
final class MemberCreationException extends BusinessException {
    private final boolean rolledBack;

    MemberCreationException(ErrorCode error, boolean rolledBack) {
        super(error);
        this.rolledBack = rolledBack;
    }

    boolean rolledBack() {
        return rolledBack;
    }
}
