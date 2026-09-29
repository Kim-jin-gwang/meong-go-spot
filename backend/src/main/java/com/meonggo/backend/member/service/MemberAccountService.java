package com.meonggo.backend.member.service;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.auth.repository.AuthSessionRepository;
import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.dto.MemberProfileResponse;
import com.meonggo.backend.member.dto.NicknameUpdateRequest;
import com.meonggo.backend.member.dto.PasswordChangeRequest;
import com.meonggo.backend.member.dto.WithdrawalRequest;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.entity.MemberStatus;
import com.meonggo.backend.member.exception.MemberErrorCode;
import com.meonggo.backend.member.repository.MemberRepository;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 본인 계정 변경 — 닉네임·비밀번호·탈퇴.
 *
 * <p>비밀번호가 필요한 두 작업(A5·A8)은 A2 로그인과 같은 정규화·Argon2 실행권·실패 제한으로 현재 비밀번호를 재인증한다. Argon2 는 행 잠금 밖에서
 * 끝내고, 짧은 트랜잭션 안에서 회원을 잠가 상태와 "검증한 그 해시"가 그대로인지 다시 확인한 뒤에만 바꾼다 — 같은 계정의 다른 기기가 동시에 비밀번호를 바꾼 경우를 놓치지
 * 않기 위해서다.
 */
@Service
public class MemberAccountService {
    private static final String DELETE_OWNED_POSTS =
            """
            update animal_case c set status='DELETED',is_matchable=false,deleted_at=current_timestamp,
              updated_at=current_timestamp,version=version+1
            from user_post p where p.animal_case_id=c.id and p.member_id=? and c.status<>'DELETED'
            """;

    private final MemberRepository members;
    private final AuthSessionRepository sessions;
    private final LoginService login;
    private final SignupInputPolicy inputs;
    private final PasswordWork passwords;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MemberAccountService(
            MemberRepository members,
            AuthSessionRepository sessions,
            LoginService login,
            SignupInputPolicy inputs,
            PasswordWork passwords,
            JdbcTemplate jdbc,
            @Qualifier("authClock") Clock clock,
            PlatformTransactionManager manager) {
        this.members = members;
        this.sessions = sessions;
        this.login = login;
        this.inputs = inputs;
        this.passwords = passwords;
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public MemberProfileResponse profile(AuthPrincipal principal) {
        return profile(activeMember(principal));
    }

    public MemberProfileResponse changeNickname(
            AuthPrincipal principal, NicknameUpdateRequest request) {
        String nickname = inputs.normalizeNickname(request.nickname());
        return transaction.execute(
                status -> {
                    Member member = lockActiveMember(principal);
                    member.changeNickname(nickname);
                    return profile(member);
                });
    }

    public void changePassword(
            AuthPrincipal principal, PasswordChangeRequest request, byte[] clientIp) {
        Member member = activeMember(principal);
        String replacement = normalizeNewPassword(request.newPassword(), member.getLoginId());
        String verifiedHash =
                reauthenticate(member, request.currentPassword(), clientIp, "currentPassword");
        // 현재 비밀번호는 방금 실제 해시와 일치했으므로 NFC 문자열 비교만으로 "같은 비밀번호"를 판정할 수 있다.
        if (replacement.equals(
                Normalizer.normalize(request.currentPassword(), Normalizer.Form.NFC)))
            throw new BusinessException(MemberErrorCode.PASSWORD_UNCHANGED);
        String encoded;
        try (var permit = passwords.acquire()) {
            encoded = permit.encode(replacement);
        }
        Instant now = now();
        mutate(
                principal,
                verifiedHash,
                locked -> {
                    locked.changePassword(encoded);
                    // 다른 기기의 세션은 끊고, 비밀번호를 바꾼 이 세션은 유지한다 (docs/auth-token-policy.md).
                    sessions.revokeAllExcept(locked.getId(), principal.sessionId(), now);
                });
    }

    public void withdraw(AuthPrincipal principal, WithdrawalRequest request, byte[] clientIp) {
        Member member = activeMember(principal);
        String verifiedHash =
                reauthenticate(member, request.currentPassword(), clientIp, "currentPassword");
        Instant now = now();
        mutate(
                principal,
                verifiedHash,
                locked -> {
                    locked.withdraw(now);
                    sessions.revokeAll(locked.getId(), now);
                    sessions.clearPushRegistrations(locked.getId());
                    jdbc.update(DELETE_OWNED_POSTS, locked.getId());
                });
    }

    /** 잠금 → 상태·해시 재확인 → 변경. 회원 → 세션 순서로 잠가 SessionService 와 교착하지 않는다. */
    private void mutate(AuthPrincipal principal, String verifiedHash, Consumer<Member> change) {
        transaction.executeWithoutResult(
                status -> {
                    Member member = lockActiveMember(principal);
                    if (!verifiedHash.equals(member.getPasswordHash()))
                        throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
                    change.accept(member);
                });
    }

    private String reauthenticate(Member member, String password, byte[] clientIp, String field) {
        try {
            return login.reauthenticate(member, password, clientIp);
        } catch (InputValidationException ex) {
            throw new InputValidationException(field, ex.getMessage());
        }
    }

    private String normalizeNewPassword(String value, String canonicalLoginId) {
        try {
            return inputs.normalizePassword(value, canonicalLoginId);
        } catch (InputValidationException ex) {
            throw new InputValidationException("newPassword", ex.getMessage());
        }
    }

    private Member activeMember(AuthPrincipal principal) {
        return requireActive(members.findById(principal.memberId()).orElse(null));
    }

    private Member lockActiveMember(AuthPrincipal principal) {
        return requireActive(members.findLockedById(principal.memberId()).orElse(null));
    }

    private static Member requireActive(Member member) {
        if (member == null) throw new BusinessException(AuthErrorCode.INVALID_SESSION);
        if (member.getStatus() != MemberStatus.ACTIVE)
            throw new BusinessException(AuthErrorCode.ACCOUNT_UNAVAILABLE);
        return member;
    }

    private static MemberProfileResponse profile(Member member) {
        return new MemberProfileResponse(member.getId(), member.getNickname());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
