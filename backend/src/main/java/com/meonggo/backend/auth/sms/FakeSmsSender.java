package com.meonggo.backend.auth.sms;

/** dev의 고정 코드 확인용 공급자. 전달값을 저장하거나 출력하지 않는다. */
public class FakeSmsSender implements SmsSender {
    @Override
    public void send(String e164PhoneNumber, String code) {
        // 실제 전송은 SOLAPI 공급자가 담당한다.
    }
}
