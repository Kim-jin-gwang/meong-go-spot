package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.exception.MemberErrorCode;

final class PrivacyCollectionConsent {
    static final String CURRENT_VERSION = "privacy-collection-v1";

    private PrivacyCollectionConsent() {}

    static void requireCurrent(Boolean agreed, String version) {
        if (!Boolean.TRUE.equals(agreed)) {
            throw new InputValidationException("privacyCollectionAgreed", "개인정보 수집·이용에 동의해 주세요.");
        }
        if (!CURRENT_VERSION.equals(version)) {
            throw new BusinessException(MemberErrorCode.CONSENT_VERSION_MISMATCH);
        }
    }
}
