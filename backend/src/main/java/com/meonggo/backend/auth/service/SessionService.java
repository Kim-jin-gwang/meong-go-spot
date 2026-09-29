package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.TokenResponse;
import com.meonggo.backend.auth.entity.AuthSession;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.repository.AuthSessionRepository;
import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.security.JwtTokenService;
import com.meonggo.backend.auth.security.RefreshToken;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.member.entity.MemberStatus;
import com.meonggo.backend.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SessionService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);
    private final AuthSessionRepository sessions;
    private final MemberRepository members;
    private final JwtTokenService jwt;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SessionService(
            AuthSessionRepository sessions,
            MemberRepository members,
            JwtTokenService jwt,
            @Qualifier("authClock") Clock clock,
            PlatformTransactionManager manager) {
        this.sessions = sessions;
        this.members = members;
        this.jwt = jwt;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // 인증 실패를 호출한 상위 트랜잭션이 rollback해도 폐기 결과는 유지한다.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public TokenResponse create(long memberId) {
        TokenResponse result =
                execute(
                        status -> {
                            var member = members.findLockedById(memberId).orElse(null);
                            Instant now = now();
                            if (member == null) return null;
                            if (member.getStatus() != MemberStatus.ACTIVE) {
                                sessions.revokeAll(memberId, now);
                                return null;
                            }
                            RefreshToken refresh = RefreshToken.create();
                            AuthSession session =
                                    sessions.saveAndFlush(
                                            new AuthSession(
                                                    memberId,
                                                    refresh.selector(),
                                                    refresh.secretHash(),
                                                    now,
                                                    now.plus(jwt.refreshTokenTtl())));
                            return tokens(session, refresh, now);
                        });
        if (result == null) throw new BusinessException(AuthErrorCode.ACCOUNT_UNAVAILABLE);
        return result;
    }

    public TokenResponse refresh(String raw) {
        RefreshToken supplied = RefreshToken.parse(raw);
        TokenResponse result =
                execute(
                        status -> {
                            AuthSession session = lockSession(supplied.selector());
                            Instant now = now();
                            if (session == null || session.isRevoked()) return null;
                            if (!session.getExpiresAt().isAfter(now)) {
                                session.revoke(now);
                                return null;
                            }
                            if (!supplied.matches(session.getRefreshTokenHash())) {
                                session.revoke(now);
                                return null;
                            }
                            RefreshToken renewed =
                                    RefreshToken.rotate(session.getRefreshTokenSelector());
                            session.rotate(renewed.secretHash(), now);
                            return tokens(session, renewed, now);
                        });
        if (result == null) throw invalid();
        return result;
    }

    public void logout(AuthPrincipal principal, String raw) {
        RefreshToken supplied = RefreshToken.parse(raw);
        Boolean valid =
                execute(
                        status -> {
                            AuthSession session = lockSession(supplied.selector());
                            Instant now = now();
                            if (session == null
                                    || session.getId() != principal.sessionId()
                                    || session.getMemberId() != principal.memberId()
                                    || !session.getExpiresAt().isAfter(now)
                                    || !supplied.matches(session.getRefreshTokenHash()))
                                return false;
                            session.revoke(now);
                            return true;
                        });
        if (!Boolean.TRUE.equals(valid)) throw invalid();
    }

    public void validate(AuthPrincipal principal) {
        Boolean valid =
                execute(
                        status -> {
                            // 모든 세션 변경 경로가 회원 -> 세션 순서로 잠가 탈퇴 세션 일괄 폐기와 교착하지 않는다.
                            var member = members.findLockedById(principal.memberId()).orElse(null);
                            Instant now = now();
                            if (member == null) return false;
                            if (member.getStatus() != MemberStatus.ACTIVE) {
                                sessions.revokeAll(member.getId(), now);
                                return false;
                            }
                            AuthSession session =
                                    sessions.findById(principal.sessionId()).orElse(null);
                            return session != null
                                    && session.getMemberId() == principal.memberId()
                                    && !session.isRevoked()
                                    && session.getExpiresAt().isAfter(now);
                        });
        if (!Boolean.TRUE.equals(valid)) throw invalid();
    }

    private <T> T execute(TransactionCallback<T> callback) {
        try {
            return transaction.execute(callback);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // PostgreSQL 제약 오류에는 selector·해시가 포함될 수 있어 원문을 로그에 전달하지 않는다.
            LOG.error(
                    "Authentication session transaction failed: {}", ex.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private AuthSession lockSession(String selector) {
        Long memberId = sessions.findMemberIdBySelector(selector).orElse(null);
        if (memberId == null) return null;
        var member = members.findLockedById(memberId).orElse(null);
        if (member == null) return null;
        if (member.getStatus() != MemberStatus.ACTIVE) {
            sessions.revokeAll(memberId, now());
            return null;
        }
        return sessions.findLockedBySelector(selector).orElse(null);
    }

    private TokenResponse tokens(AuthSession session, RefreshToken refresh, Instant now) {
        var access = jwt.issue(session.getMemberId(), session.getId(), now);
        return new TokenResponse(
                "Bearer",
                access.value(),
                refresh.value(),
                access.expiresAt(),
                session.getExpiresAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private BusinessException invalid() {
        return new BusinessException(AuthErrorCode.INVALID_SESSION);
    }
}
