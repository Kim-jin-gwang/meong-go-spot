package com.meonggo.backend.member.exception;

import com.meonggo.backend.global.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MemberErrorCode implements ErrorCode {
    LOGIN_ID_IN_USE("MEMBER-001", "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요."),
    CONSENT_VERSION_MISMATCH("MEMBER-002", "최신 개인정보 수집·이용 내용을 확인해 주세요."),
    PASSWORD_UNCHANGED("MEMBER-003", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");

    private final String code;
    private final String message;

    MemberErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
