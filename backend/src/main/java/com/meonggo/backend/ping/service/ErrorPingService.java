package com.meonggo.backend.ping.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.ping.exception.PingErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 오류 응답 참고 예제용 — 의도적으로 예외를 발생시킨다. dev 프로필에서만 등록된다. */
@Service
@Profile("dev")
public class ErrorPingService {

    public void throwBusinessError() {
        throw new BusinessException(PingErrorCode.BUSINESS_ERROR);
    }

    public void throwUnexpectedError() {
        throw new IllegalStateException("의도적으로 발생시킨 예상하지 못한 예외");
    }
}
