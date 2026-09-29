package com.meonggo.backend.auth.service;

import com.meonggo.backend.auth.dto.LoginRequest;
import com.meonggo.backend.auth.dto.LoginResponse;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.repository.LoginAttemptStore;
import com.meonggo.backend.auth.security.LoginIdentityProtection;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.repository.MemberRepository;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private static final String DUMMY_INPUT = "DummyAuthenticationInput206!";
    private final SignupInputPolicy inputs;
    private final MemberRepository members;
    private final LoginAttemptStore attempts;
    private final LoginIdentityProtection protection;
    private final PasswordWork passwords;
    private final SessionService sessions;
    private final String dummyHash;

    public LoginService(
            SignupInputPolicy inputs,
            MemberRepository members,
            LoginAttemptStore attempts,
            LoginIdentityProtection protection,
            PasswordWork passwords,
            SessionService sessions) {
        this.inputs = inputs;
        this.members = members;
        this.attempts = attempts;
        this.protection = protection;
        this.passwords = passwords;
        this.sessions = sessions;
        // 요청 중에는 생성하지 않고 실제 비밀번호와 같은 프로필의 비용을 한 번 준비한다.
        try (var permit = passwords.acquire()) {
            dummyHash = permit.encode(DUMMY_INPUT);
        }
    }

    public LoginResponse login(LoginRequest request, byte[] clientIp) {
        String canonicalId = inputs.canonicalLoginId(request.loginId());
        String normalized = inputs.normalizeLoginPassword(request.password());
        Member member = members.findByLoginId(canonicalId).orElse(null);
        String accountHash = protection.accountHash(canonicalId);
        String ipHash = protection.ipHash(clientIp);
        attempts.check(accountHash, ipHash);
        try (var permit = passwords.acquire()) {
            attempts.check(accountHash, ipHash);
            boolean matched = false;
            if (member != null && normalized != null) {
                matched = permit.matches(normalized, member.getPasswordHash());
            } else {
                permit.matches(DUMMY_INPUT, dummyHash);
            }
            if (!matched) {
                attempts.failure(accountHash, ipHash);
                throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
            }
            // 생성 트랜잭션이 회원 상태를 다시 확인하고 비활성 회원의 잔여 세션을 폐기한다.
            var tokens = sessions.create(member.getId());
            attempts.success(accountHash);
            return LoginResponse.of(tokens, member.getId(), member.getNickname());
        }
    }

    /**
     * 로그인한 회원의 현재 비밀번호 재인증 (A5 탈퇴·A8 비밀번호 변경).
     *
     * <p>A2 와 같은 NFC 정규화·형식 검사·dummy 비교·실행권을 쓰고, 실패는 그 계정의 로그인 실패 횟수에 함께 쌓인다 — 탈취한 세션으로 비밀번호를 무한히
     * 추측하지 못하게 한다. 성공하면 방금 일치한 해시를 돌려주어 호출자가 잠금 안에서 "그 사이 바뀌지 않았는지" 다시 확인할 수 있게 한다.
     */
    public String reauthenticate(Member member, String password, byte[] clientIp) {
        String normalized = inputs.normalizeLoginPassword(password);
        String accountHash = protection.accountHash(member.getLoginId());
        String ipHash = protection.ipHash(clientIp);
        attempts.check(accountHash, ipHash);
        try (var permit = passwords.acquire()) {
            attempts.check(accountHash, ipHash);
            boolean matched = false;
            if (normalized != null) {
                matched = permit.matches(normalized, member.getPasswordHash());
            } else {
                permit.matches(DUMMY_INPUT, dummyHash);
            }
            if (!matched) {
                attempts.failure(accountHash, ipHash);
                throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
            }
            attempts.success(accountHash);
            return member.getPasswordHash();
        }
    }
}
