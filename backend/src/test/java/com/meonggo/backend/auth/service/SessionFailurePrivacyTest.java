package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.meonggo.backend.auth.repository.AuthSessionRepository;
import com.meonggo.backend.auth.security.JwtTokenService;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.repository.MemberRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class SessionFailurePrivacyTest {
    @Test
    void databaseFailureCannotExposeCredentialValuesInExceptionChain() {
        var repository = mock(AuthSessionRepository.class);
        var members = mock(MemberRepository.class);
        var jwt = mock(JwtTokenService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jwt.refreshTokenTtl()).thenReturn(Duration.ofDays(30));
        var member =
                new Member(
                        "owner",
                        "test-hash",
                        "보호자",
                        "test-envelope",
                        "phone-hash",
                        Instant.now(),
                        true,
                        "privacy-collection-v1",
                        Instant.now());
        when(members.findLockedById(1)).thenReturn(Optional.of(member));
        when(repository.saveAndFlush(any()))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "private-selector-and-hash-in-db-detail"));
        var sessions = new SessionService(repository, members, jwt, Clock.systemUTC(), manager);
        assertThatThrownBy(() -> sessions.create(1))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> {
                            assertThat(ex.errorCode().code()).isEqualTo("COMMON-500");
                            assertThat(ex)
                                    .hasNoCause()
                                    .hasMessageNotContaining(
                                            "private-selector-and-hash-in-db-detail");
                        });
    }
}
