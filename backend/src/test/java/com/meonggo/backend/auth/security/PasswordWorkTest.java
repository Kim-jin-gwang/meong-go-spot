package com.meonggo.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import org.junit.jupiter.api.Test;

class PasswordWorkTest {
    @Test
    void encodesAndMatchesUsingPinnedArgon2idProfile() {
        PasswordWork work = new PasswordWork(1);

        try (PasswordWork.Permit permit = work.acquire()) {
            String encoded = permit.encode("a-safe-password-2026!");

            assertThat(encoded).startsWith("{argon2id-v1}$argon2id$v=19$m=65536,t=3,p=4$");
            assertThat(permit.matches("a-safe-password-2026!", encoded)).isTrue();
            assertThat(permit.matches("a-wrong-password-2026!", encoded)).isFalse();
        }
    }

    @Test
    void failsImmediatelyWhenAllPermitsAreBusyAndCloseIsIdempotent() {
        PasswordWork work = new PasswordWork(1);
        PasswordWork.Permit first = work.acquire();

        assertThatThrownBy(work::acquire)
                .isInstanceOfSatisfying(
                        RetryableAuthException.class,
                        exception -> {
                            assertThat(exception.errorCode())
                                    .isEqualTo(AuthErrorCode.AUTH_UNAVAILABLE);
                            assertThat(exception.retryAfterSeconds()).isEqualTo(1);
                        });

        first.close();
        first.close();
        try (PasswordWork.Permit ignored = work.acquire()) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void rejectsConcurrencyOutsideOneThroughFour() {
        assertThatThrownBy(() -> new PasswordWork(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordWork(5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releasesPermitAfterWorkThrowsAndRejectsUseAfterClose() {
        PasswordWork work = new PasswordWork(1);
        PasswordWork.Permit closed;

        assertThatThrownBy(
                        () -> {
                            try (PasswordWork.Permit permit = work.acquire()) {
                                permit.encode(null);
                            }
                        })
                .isInstanceOf(RuntimeException.class);
        try (PasswordWork.Permit ignored = work.acquire()) {
            closed = ignored;
        }

        assertThatThrownBy(() -> closed.encode("a-safe-password-2026!"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> closed.matches("a-safe-password-2026!", "invalid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsExactlyConfiguredNumberOfSimultaneousPermits() {
        PasswordWork work = new PasswordWork(4);
        PasswordWork.Permit first = work.acquire();
        PasswordWork.Permit second = work.acquire();
        PasswordWork.Permit third = work.acquire();
        PasswordWork.Permit fourth = work.acquire();

        assertThatThrownBy(work::acquire).isInstanceOf(RetryableAuthException.class);

        first.close();
        second.close();
        third.close();
        fourth.close();
    }
}
