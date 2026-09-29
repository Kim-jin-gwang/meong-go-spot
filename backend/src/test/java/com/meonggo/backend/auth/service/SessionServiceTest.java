package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.security.JwtTokenService;
import com.meonggo.backend.global.error.BusinessException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class SessionServiceTest {
    @MockitoBean private LoginService login;
    @Autowired private SessionService sessions;
    @Autowired private JwtTokenService jwt;
    @Autowired private JdbcTemplate jdbc;
    private long memberId;

    @BeforeEach
    void setup() {
        jdbc.update("delete from member where login_id = 'session-test-owner'");
        memberId =
                jdbc.queryForObject(
                        """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                  phone_ciphertext,phone_lookup_hash,phone_verified_at,
                  privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values ('session-test-owner','test-only-hash','보호자','ACTIVE',now(),now(),
                  'test-envelope',repeat('9',64),now(),true, 'privacy-collection-v1',now()) returning id
                """,
                        Long.class);
    }

    @Test
    void rotationKeepsSessionAndExpiryAndReplayRevocationCommits() {
        var initial = sessions.create(memberId);
        var principal = jwt.verify(initial.accessToken());
        var renewed = sessions.refresh(initial.refreshToken());
        assertThat(renewed.refreshTokenExpiresAt()).isEqualTo(initial.refreshTokenExpiresAt());
        assertThat(jwt.verify(renewed.accessToken())).isEqualTo(principal);
        assertThat(renewed.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertInvalid(() -> sessions.refresh(initial.refreshToken()));
        assertInvalid(() -> sessions.validate(principal));
        assertInvalid(() -> sessions.refresh(renewed.refreshToken()));
        assertThat(revoked(principal)).isTrue();
    }

    @Test
    void concurrentRefreshSucceedsExactlyOnceAndLoserRevokesSession() throws Exception {
        var token = sessions.create(memberId);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> action =
                    () -> {
                        start.await();
                        try {
                            sessions.refresh(token.refreshToken());
                            return true;
                        } catch (BusinessException ex) {
                            assertThat(ex.errorCode().code()).isEqualTo("AUTH-003");
                            return false;
                        }
                    };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertInvalid(() -> sessions.validate(jwt.verify(token.accessToken())));
    }

    @Test
    void logoutIsIdempotentButCannotLogOutAnotherDevice() {
        var first = sessions.create(memberId);
        var second = sessions.create(memberId);
        var owner = jwt.verify(first.accessToken());
        assertInvalid(() -> sessions.logout(owner, second.refreshToken()));
        sessions.validate(jwt.verify(second.accessToken()));
        sessions.logout(owner, first.refreshToken());
        sessions.logout(owner, first.refreshToken());
        assertInvalid(() -> sessions.validate(owner));
        sessions.validate(jwt.verify(second.accessToken()));
    }

    @Test
    void inactiveMemberRevokesEveryRemainingSession() {
        var first = sessions.create(memberId);
        var second = sessions.create(memberId);
        jdbc.update(
                "update member set status = 'WITHDRAWN', deleted_at = now() where id = ?",
                memberId);
        assertInvalid(() -> sessions.validate(jwt.verify(first.accessToken())));
        assertThat(revoked(jwt.verify(first.accessToken()))).isTrue();
        assertThat(revoked(jwt.verify(second.accessToken()))).isTrue();
    }

    @Test
    void expiryAndMemberBindingAreCheckedWithoutJwtClockSkew() {
        var token = sessions.create(memberId);
        var principal = jwt.verify(token.accessToken());
        assertInvalid(
                () -> sessions.validate(new AuthPrincipal(memberId + 1, principal.sessionId())));
        jdbc.update(
                "update auth_session set created_at=now()-interval '31 days', expires_at=now()-interval '1 second' where id=?",
                principal.sessionId());
        assertInvalid(() -> sessions.validate(principal));
        assertInvalid(() -> sessions.refresh(token.refreshToken()));
        assertThat(revoked(principal)).isTrue();
        assertInvalid(() -> sessions.logout(principal, token.refreshToken()));
    }

    @Test
    void oldRefreshCannotLogOutRotatedSession() {
        var initial = sessions.create(memberId);
        var renewed = sessions.refresh(initial.refreshToken());
        var principal = jwt.verify(initial.accessToken());
        assertInvalid(() -> sessions.logout(principal, initial.refreshToken()));
        sessions.validate(principal);
        sessions.logout(principal, renewed.refreshToken());
        assertThat(revoked(principal)).isTrue();
    }

    @Test
    void refreshOfInactiveMemberCommitsRevocationOfAllSessions() {
        var first = sessions.create(memberId);
        var second = sessions.create(memberId);
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", memberId);
        assertInvalid(() -> sessions.refresh(first.refreshToken()));
        assertThat(revoked(jwt.verify(first.accessToken()))).isTrue();
        assertThat(revoked(jwt.verify(second.accessToken()))).isTrue();
    }

    private boolean revoked(AuthPrincipal principal) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select revoked_at is not null from auth_session where id=?",
                        Boolean.class,
                        principal.sessionId()));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode().code()).isEqualTo("AUTH-003"));
    }
}
