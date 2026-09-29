package com.meonggo.backend.auth.security;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Owns the process-wide, non-queuing permits for all Argon2 password work. */
public final class PasswordWork {
    private static final String ID = "{argon2id-v1}";

    private final Semaphore permits;
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65_536, 3);

    public PasswordWork(int concurrency) {
        if (concurrency < 1 || concurrency > 4) {
            throw new IllegalArgumentException("Argon2 동시 실행 수는 1~4여야 합니다.");
        }
        this.permits = new Semaphore(concurrency);
    }

    public Permit acquire() {
        if (!permits.tryAcquire()) {
            throw new RetryableAuthException(AuthErrorCode.AUTH_UNAVAILABLE, 1);
        }
        return new Permit();
    }

    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit() {}

        public String encode(String normalized) {
            ensureOpen();
            return ID + encoder.encode(Objects.requireNonNull(normalized, "normalized"));
        }

        public boolean matches(String normalized, String encoded) {
            ensureOpen();
            Objects.requireNonNull(normalized, "normalized");
            if (encoded == null || !encoded.startsWith(ID)) {
                return false;
            }
            return encoder.matches(normalized, encoded.substring(ID.length()));
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }

        private void ensureOpen() {
            if (closed.get()) {
                throw new IllegalStateException("반환한 비밀번호 실행권은 사용할 수 없습니다.");
            }
        }
    }
}
