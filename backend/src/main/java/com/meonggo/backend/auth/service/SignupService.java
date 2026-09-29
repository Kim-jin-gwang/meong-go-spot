package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.SignupRequest;
import com.meonggo.backend.auth.dto.SignupResponse;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.auth.repository.PhoneVerificationStore;
import com.meonggo.backend.auth.repository.PhoneVerificationStore.ProofSnapshot;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.auth.security.TestPhoneSignupBypass;
import com.meonggo.backend.member.entity.Member;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SignupService {
    private static final Logger LOG = LoggerFactory.getLogger(SignupService.class);
    private final SignupInputPolicy inputs;
    private final PhoneProtection protection;
    private final PasswordWork passwords;
    private final PhoneVerificationStore store;
    private final MemberCreationTransaction creation;
    private final TestPhoneSignupBypass testPhones;

    public SignupService(
            SignupInputPolicy inputs,
            PhoneProtection protection,
            PasswordWork passwords,
            PhoneVerificationStore store,
            MemberCreationTransaction creation,
            TestPhoneSignupBypass testPhones) {
        this.inputs = inputs;
        this.protection = protection;
        this.passwords = passwords;
        this.store = store;
        this.creation = creation;
        this.testPhones = testPhones;
    }

    public SignupResponse signup(SignupRequest request) {
        String loginId = inputs.canonicalLoginId(request.loginId());
        String password = inputs.normalizePassword(request.password(), loginId);
        String nickname = inputs.normalizeNickname(request.nickname());
        String bypassPhone = testPhones.normalizedPhone(request.phoneNumber());
        String phone =
                bypassPhone == null ? protection.normalize(request.phoneNumber()) : bypassPhone;
        PrivacyCollectionConsent.requireCurrent(
                request.privacyCollectionAgreed(), request.privacyCollectionPolicyVersion());
        String phoneHash = protection.lookupHash(phone);
        if (bypassPhone != null) {
            return createWithoutProof(
                    loginId,
                    password,
                    nickname,
                    phone,
                    phoneHash,
                    request.privacyCollectionAgreed());
        }
        if (request.phoneVerificationToken() == null
                || request.phoneVerificationToken().isBlank()) {
            throw new InputValidationException("phoneVerificationToken", "휴대전화 인증 정보가 필요합니다.");
        }
        PhoneProof proof = PhoneProof.parse(request.phoneVerificationToken());
        ProofSnapshot snapshot = store.findProof(proof.selector()).orElseThrow(PhoneProof::invalid);
        if (!PhoneVerificationService.constantEquals(snapshot.secretHash(), proof.secretHash())
                || !PhoneVerificationService.constantEquals(snapshot.phoneLookupHash(), phoneHash)
                || !"ISSUED".equals(snapshot.status())
                || !snapshot.expiresAt().isAfter(Instant.now())) {
            throw PhoneProof.invalid();
        }
        String requestId = UUID.randomUUID().toString();
        try (var work = passwords.acquire()) {
            if (!store.claimProof(proof.selector(), proof.secretHash(), phoneHash, requestId)) {
                throw PhoneProof.invalid();
            }
            Member member;
            try {
                member =
                        new Member(
                                loginId,
                                work.encode(password),
                                nickname,
                                protection.encrypt(phone),
                                phoneHash,
                                snapshot.issuedAt(),
                                request.privacyCollectionAgreed(),
                                PrivacyCollectionConsent.CURRENT_VERSION,
                                Instant.now());
            } catch (RuntimeException ex) {
                restore(proof.selector(), requestId);
                throw new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
            }
            SignupResponse response;
            try {
                response = creation.create(member, phoneHash);
            } catch (MemberCreationException ex) {
                if (ex.rolledBack()) {
                    restore(proof.selector(), requestId);
                }
                throw ex;
            }
            try {
                store.consumeProof(proof.selector(), requestId);
            } catch (RetryableAuthException ex) {
                // DB 커밋은 끝났다. CLAIMED와 DB 유일 제약을 유지하고 성공한 가입을 반환한다.
                LOG.warn("Signup committed; proof consumption deferred until expiry");
            }
            return response;
        }
    }

    private SignupResponse createWithoutProof(
            String loginId,
            String password,
            String nickname,
            String phone,
            String phoneHash,
            Boolean privacyCollectionAgreed) {
        try (var work = passwords.acquire()) {
            Instant now = Instant.now();
            Member member;
            try {
                member =
                        new Member(
                                loginId,
                                work.encode(password),
                                nickname,
                                protection.encrypt(phone),
                                phoneHash,
                                now,
                                privacyCollectionAgreed,
                                PrivacyCollectionConsent.CURRENT_VERSION,
                                now);
            } catch (RuntimeException ex) {
                throw new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
            }
            return creation.create(member, phoneHash);
        }
    }

    private void restore(String selector, String requestId) {
        try {
            store.restoreProof(selector, requestId);
        } catch (RetryableAuthException ex) {
            LOG.warn("Signup proof restoration unavailable; claim retained until expiry");
        }
    }
}
