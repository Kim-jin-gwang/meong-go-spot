package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.SignupResponse;
import com.meonggo.backend.auth.exception.PhoneErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.global.error.ErrorCode;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.exception.MemberErrorCode;
import com.meonggo.backend.member.repository.MemberRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 커밋 완료와 확정 rollback을 구분해 외부 Redis 증명의 복구 여부를 결정한다. */
@Service
public class MemberCreationTransaction {
    private final MemberRepository members;
    private final TransactionTemplate transaction;

    public MemberCreationTransaction(MemberRepository members, PlatformTransactionManager manager) {
        this.members = members;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SignupResponse create(Member member, String phoneHash) {
        AtomicInteger outcome = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        AtomicBoolean memberWorkStarted = new AtomicBoolean();
        try {
            return transaction.execute(
                    status -> {
                        memberWorkStarted.set(true);
                        TransactionSynchronizationManager.registerSynchronization(
                                new TransactionSynchronization() {
                                    @Override
                                    public void afterCompletion(int completionStatus) {
                                        outcome.set(completionStatus);
                                    }
                                });
                        if (members.existsByLoginId(member.getLoginId())) {
                            throw new BusinessException(MemberErrorCode.LOGIN_ID_IN_USE);
                        }
                        if (members.existsByPhoneLookupHash(phoneHash)) {
                            throw new BusinessException(PhoneErrorCode.PHONE_IN_USE);
                        }
                        Member saved = members.saveAndFlush(member);
                        return new SignupResponse(
                                saved.getId(),
                                saved.getLoginId(),
                                saved.getNickname(),
                                saved.getCreatedAt());
                    });
        } catch (RuntimeException ex) {
            throw new MemberCreationException(
                    errorCode(ex),
                    !memberWorkStarted.get()
                            || outcome.get() == TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private ErrorCode errorCode(RuntimeException failure) {
        if (failure instanceof BusinessException business) {
            return business.errorCode();
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                if ("idx_member_login_id_canonical".equals(constraint.getConstraintName())) {
                    return MemberErrorCode.LOGIN_ID_IN_USE;
                }
                if ("idx_member_active_phone_lookup_hash".equals(constraint.getConstraintName())) {
                    return PhoneErrorCode.PHONE_IN_USE;
                }
            }
        }
        return CommonErrorCode.INTERNAL_SERVER_ERROR;
    }
}
