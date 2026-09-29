package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.AccountRecoveryConfirmationResponse;
import com.meonggo.backend.auth.dto.PasswordResetRequest;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.auth.exception.PhoneErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.auth.repository.AuthSessionRepository;
import com.meonggo.backend.auth.repository.PhoneVerificationStore;
import com.meonggo.backend.auth.repository.PhoneVerificationStore.OtpSnapshot;
import com.meonggo.backend.auth.repository.PhoneVerificationStore.ProofRecord;
import com.meonggo.backend.auth.repository.PhoneVerificationStore.ProofSnapshot;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.auth.service.PhoneVerificationService.OtpGenerator;
import com.meonggo.backend.auth.sms.SmsSender;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.entity.MemberStatus;
import com.meonggo.backend.member.exception.MemberErrorCode;
import com.meonggo.backend.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 찾기 (A9) — 가입 때 인증한 휴대전화로 아이디를 되찾고 비밀번호를 재설정한다.
 *
 * <p>OTP·증명은 가입(A0)과 같은 Redis 저장소·제한을 쓰지만 키 공간을 {@value #SCOPE_PREFIX} 로 나눈다. 가입용 증명으로 남의 비밀번호를
 * 바꾸거나, 복구용 증명으로 가입하는 일이 구조적으로 불가능해야 한다.
 *
 * <p>가입 여부는 응답으로 드러내지 않는다. 미가입 번호에도 202 를 주고 문자만 보내지 않는다 — 번호 열거를 막는다.
 */
@Service
public class AccountRecoveryService {
    static final String SCOPE_PREFIX = "recovery:";

    private final PhoneProtection protection;
    private final PhoneVerificationStore store;
    private final SmsSender sms;
    private final MemberRepository members;
    private final OtpGenerator generator;
    private final SignupInputPolicy inputs;
    private final PasswordWork passwords;
    private final AuthSessionRepository sessions;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AccountRecoveryService(
            PhoneProtection protection,
            PhoneVerificationStore store,
            SmsSender sms,
            MemberRepository members,
            OtpGenerator generator,
            SignupInputPolicy inputs,
            PasswordWork passwords,
            AuthSessionRepository sessions,
            @Qualifier("authClock") Clock clock,
            PlatformTransactionManager manager) {
        this.protection = protection;
        this.store = store;
        this.sms = sms;
        this.members = members;
        this.generator = generator;
        this.inputs = inputs;
        this.passwords = passwords;
        this.sessions = sessions;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    /** A9-1. 가입된 번호면 인증 문자를 보낸다. 아니어도 같은 202 — 제한 카운터는 똑같이 소비한다. */
    public void requestCode(String phoneNumber, byte[] clientIp) {
        String phone = protection.normalize(phoneNumber);
        String phoneHash = protection.lookupHash(phone);
        String scope = scope(phoneHash);
        String requestId = UUID.randomUUID().toString();
        long retryAfter = store.reserveSend(scope, protection.ipHash(clientIp), requestId);
        if (retryAfter > 0) {
            throw new RetryableAuthException(PhoneErrorCode.RATE_LIMITED, retryAfter);
        }
        if (activeMember(phoneHash).isEmpty()) {
            return;
        }
        String code = generator.generate();
        String verificationId = UUID.randomUUID().toString();
        sms.send(phone, code);
        if (!store.activateOtp(
                scope, requestId, verificationId, protection.otpHash(verificationId, code))) {
            throw new RetryableAuthException(PhoneErrorCode.SMS_UNAVAILABLE, 60);
        }
    }

    /** A9-2. 코드가 맞으면 아이디와 10분짜리 복구 증명을 돌려준다. 번호 소유를 방금 증명했으므로 아이디는 가리지 않는다. */
    public AccountRecoveryConfirmationResponse confirm(String phoneNumber, String code) {
        String phoneHash = protection.lookupHash(protection.normalize(phoneNumber));
        String scope = scope(phoneHash);
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new InputValidationException("verificationCode", "인증 코드는 숫자 6자리여야 합니다.");
        }
        OtpSnapshot otp = store.findOtp(scope).orElseThrow(PhoneProof::invalid);
        boolean matches =
                PhoneVerificationService.constantEquals(
                        otp.hash(), protection.otpHash(otp.verificationId(), code));
        PhoneProof proof = PhoneProof.generate();
        Instant issuedAt = Instant.now();
        ProofRecord record =
                new ProofRecord(
                        proof.selector(),
                        proof.secretHash(),
                        scope,
                        issuedAt,
                        issuedAt.plusSeconds(600));
        if (!store.confirmOtp(scope, otp.verificationId(), otp.hash(), matches, record)) {
            throw PhoneProof.invalid();
        }
        Member member = activeMember(phoneHash).orElseThrow(PhoneProof::invalid);
        ProofSnapshot stored = store.findProof(proof.selector()).orElseThrow(PhoneProof::invalid);
        return new AccountRecoveryConfirmationResponse(
                member.getLoginId(), proof.token(), stored.expiresAt());
    }

    /** A9-3. 증명이 그 아이디의 번호에 묶여 있으면 비밀번호를 바꾸고 모든 세션을 끊는다. */
    public void resetPassword(PasswordResetRequest request) {
        String loginId = inputs.canonicalLoginId(request.loginId());
        String replacement = normalizeNewPassword(request.newPassword(), loginId);
        PhoneProof proof = PhoneProof.parse(request.recoveryToken());
        ProofSnapshot snapshot = store.findProof(proof.selector()).orElseThrow(PhoneProof::invalid);
        Member member = activeMemberByLoginId(loginId).orElseThrow(PhoneProof::invalid);
        String scope = scope(member.getPhoneLookupHash());
        if (!PhoneVerificationService.constantEquals(snapshot.secretHash(), proof.secretHash())
                || !PhoneVerificationService.constantEquals(snapshot.phoneLookupHash(), scope)
                || !"ISSUED".equals(snapshot.status())
                || !snapshot.expiresAt().isAfter(Instant.now())) {
            throw PhoneProof.invalid();
        }
        String requestId = UUID.randomUUID().toString();
        String encoded;
        try (var permit = passwords.acquire()) {
            // 잊은 비밀번호를 그대로 다시 넣는 경우 — A8 과 같은 규칙으로 거부한다.
            if (permit.matches(replacement, member.getPasswordHash())) {
                throw new BusinessException(MemberErrorCode.PASSWORD_UNCHANGED);
            }
            if (!store.claimProof(proof.selector(), proof.secretHash(), scope, requestId)) {
                throw PhoneProof.invalid();
            }
            encoded = permit.encode(replacement);
        }
        Instant now = Instant.now(clock);
        try {
            transaction.executeWithoutResult(
                    status -> {
                        Member locked =
                                members.findLockedById(member.getId())
                                        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                                        .orElseThrow(PhoneProof::invalid);
                        locked.changePassword(encoded);
                        // 비밀번호를 잊은 사람이 다른 기기에 로그인돼 있을 리 없다 — 전부 끊는다 (A8 은 요청 세션을 남기지만
                        // 여기는 남길 세션이 없다).
                        sessions.revokeAll(locked.getId(), now);
                    });
        } catch (RuntimeException exception) {
            store.restoreProof(proof.selector(), requestId);
            throw exception;
        }
        store.consumeProof(proof.selector(), requestId);
    }

    private String normalizeNewPassword(String value, String canonicalLoginId) {
        try {
            return inputs.normalizePassword(value, canonicalLoginId);
        } catch (InputValidationException ex) {
            throw new InputValidationException("newPassword", ex.getMessage());
        }
    }

    private Optional<Member> activeMember(String phoneHash) {
        try {
            return members.findByPhoneLookupHashAndStatus(phoneHash, MemberStatus.ACTIVE);
        } catch (DataAccessException ex) {
            throw new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
        }
    }

    private Optional<Member> activeMemberByLoginId(String loginId) {
        try {
            return members.findByLoginId(loginId).filter(m -> m.getStatus() == MemberStatus.ACTIVE);
        } catch (DataAccessException ex) {
            throw new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
        }
    }

    private static String scope(String phoneHash) {
        return SCOPE_PREFIX + phoneHash;
    }
}
