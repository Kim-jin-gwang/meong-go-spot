package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.meonggo.backend.auth.dto.LoginRequest;
import com.meonggo.backend.auth.dto.TokenResponse;
import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.meonggo.backend.auth.repository.LoginAttemptStore;
import com.meonggo.backend.auth.security.LoginIdentityProtection;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.security.SignupInputPolicy;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.repository.MemberRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LoginServiceTest {
    private final MemberRepository members = mock(MemberRepository.class);
    private final LoginAttemptStore attempts = mock(LoginAttemptStore.class);
    private final LoginIdentityProtection protection = mock(LoginIdentityProtection.class);
    private final PasswordWork passwords = mock(PasswordWork.class);
    private final PasswordWork.Permit permit = mock(PasswordWork.Permit.class);
    private final SessionService sessions = mock(SessionService.class);
    private LoginService login;

    @BeforeEach
    void setup() {
        when(protection.accountHash(anyString())).thenReturn("account-hmac");
        when(protection.ipHash(org.mockito.ArgumentMatchers.any())).thenReturn("ip-hmac");
        when(passwords.acquire()).thenReturn(permit);
        when(permit.encode(anyString())).thenReturn("startup-dummy-hash");
        login =
                new LoginService(
                        new SignupInputPolicy(),
                        members,
                        attempts,
                        protection,
                        passwords,
                        sessions);
        verify(permit).encode(anyString());
        clearInvocations(passwords, permit);
    }

    @Test
    void dummyComparisonResultNeverAuthenticatesUnknownMember() {
        when(members.findByLoginId("unknown")).thenReturn(Optional.empty());
        when(permit.matches(anyString(), anyString())).thenReturn(true);
        assertInvalid(
                () -> login.login(new LoginRequest("UNKNOWN", "AllowedPassword206!"), new byte[4]));
        verify(permit, times(1)).matches("DummyAuthenticationInput206!", "startup-dummy-hash");
        verify(permit, never()).encode(anyString());
        verify(permit).close();
        verifyNoInteractions(sessions);
    }

    @Test
    void invalidPresentPasswordUsesDummyEvenForExistingMember() {
        when(members.findByLoginId("owner")).thenReturn(Optional.of(member()));
        assertInvalid(() -> login.login(new LoginRequest("owner", ""), new byte[4]));
        verify(permit, times(1)).matches("DummyAuthenticationInput206!", "startup-dummy-hash");
        verifyNoInteractions(sessions);
    }

    @Test
    void saturatedPermitDoesNotHashOrCountFailure() {
        when(passwords.acquire())
                .thenThrow(new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1));
        assertThatThrownBy(
                        () ->
                                login.login(
                                        new LoginRequest("owner", "AllowedPassword206!"),
                                        new byte[4]))
                .isInstanceOfSatisfying(
                        RetryableAuthException.class,
                        ex -> {
                            assertThat(ex.errorCode().code()).isEqualTo("AUTH-006");
                            assertThat(ex.retryAfterSeconds()).isEqualTo(1);
                        });
        verify(attempts, never()).failure(anyString(), anyString());
        verifyNoInteractions(permit, sessions);
    }

    @Test
    void limitActivatedAfterPermitAcquisitionStopsHashAndReleasesPermit() {
        org.mockito.Mockito.doNothing()
                .doThrow(new RetryableAuthException(AuthErrorCode.LOGIN_LIMITED, 900))
                .when(attempts)
                .check("account-hmac", "ip-hmac");
        assertThatThrownBy(
                        () ->
                                login.login(
                                        new LoginRequest("owner", "AllowedPassword206!"),
                                        new byte[4]))
                .isInstanceOf(RetryableAuthException.class);
        verify(permit, never()).matches(anyString(), anyString());
        verify(permit).close();
        verify(attempts, never()).failure(anyString(), anyString());
    }

    @Test
    void realComparisonUsesNfcAndSuccessfulAuthenticationClearsOnlyAccount() {
        when(members.findByLoginId("owner")).thenReturn(Optional.of(member()));
        when(permit.matches("AllowedPasswordé", "stored-password-hash")).thenReturn(true);
        when(sessions.create(12))
                .thenReturn(
                        new TokenResponse(
                                "Bearer", "access", "refresh", Instant.now(), Instant.now()));
        var result = login.login(new LoginRequest("OWNER", "AllowedPassworde\u0301"), new byte[4]);
        assertThat(result.member().memberId()).isEqualTo(12);
        verify(members, times(1)).findByLoginId("owner");
        verify(attempts, times(2)).check("account-hmac", "ip-hmac");
        verify(permit, times(1)).matches("AllowedPasswordé", "stored-password-hash");
        verify(attempts).success("account-hmac");
        verify(attempts, never()).failure(anyString(), anyString());
    }

    private Member member() {
        var member =
                new Member(
                        "owner",
                        "stored-password-hash",
                        "보호자",
                        "test-envelope",
                        "test-hash",
                        Instant.now(),
                        true,
                        "privacy-collection-v1",
                        Instant.now());
        ReflectionTestUtils.setField(member, "id", 12L);
        return member;
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode().code()).isEqualTo("AUTH-001"));
    }
}
