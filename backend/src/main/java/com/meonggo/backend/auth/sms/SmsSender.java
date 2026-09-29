package com.meonggo.backend.auth.sms;

public interface SmsSender {
    /** 명시적 접수 성공에만 정상 반환한다. 원문을 로그에 기록하지 않는다. */
    void send(String e164PhoneNumber, String code);
}
