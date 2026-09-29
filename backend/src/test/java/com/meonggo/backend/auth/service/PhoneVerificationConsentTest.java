package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.auth.repository.PhoneVerificationStore;
import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.sms.SmsSender;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PhoneVerificationConsentTest {
    @Mock private PhoneProtection protection;
    @Mock private PhoneVerificationStore store;
    @Mock private SmsSender sms;
    @Mock private MemberRepository members;
    @Mock private PhoneVerificationService.OtpGenerator generator;
    private PhoneVerificationService service;

    @BeforeEach
    void setup() {
        service = new PhoneVerificationService(protection, store, sms, members, generator);
    }

    @Test
    void requestRejectsMissingConsentBeforeAnyPhoneProcessing() {
        assertThatThrownBy(
                        () ->
                                service.requestCode(
                                        "01012345678", new byte[0], false, "privacy-collection-v1"))
                .isInstanceOf(InputValidationException.class);
        verifyNoInteractions(protection, store, sms, members, generator);
    }

    @Test
    void confirmationRejectsOutdatedPolicyBeforeConsumingOtp() {
        assertThatThrownBy(() -> service.confirm("01012345678", "123456", true, "outdated"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(protection, store, sms, members, generator);
    }
}
