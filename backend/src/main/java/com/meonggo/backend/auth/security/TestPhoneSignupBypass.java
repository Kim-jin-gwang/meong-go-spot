package com.meonggo.backend.auth.security;

/** Fake SMS 환경에서만 사용하는 가입용 테스트 전화번호 정책이다. */
public final class TestPhoneSignupBypass {
    static final String NORMALIZED_PHONE = "+821100000000";
    private static final String FORMATTED_PHONE = "011-0000-0000";
    private static final String DIGITS_PHONE = "01100000000";

    private final boolean enabled;

    public TestPhoneSignupBypass(boolean enabled) {
        this.enabled = enabled;
    }

    public String normalizedPhone(String value) {
        if (!enabled || (!FORMATTED_PHONE.equals(value) && !DIGITS_PHONE.equals(value))) {
            return null;
        }
        return NORMALIZED_PHONE;
    }
}
