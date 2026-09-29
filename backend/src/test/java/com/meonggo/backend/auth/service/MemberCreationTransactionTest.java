package com.meonggo.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.repository.MemberRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class MemberCreationTransactionTest {
    @Test
    void beginFailureIsSafeToRestoreBecauseMemberWorkNeverStarted() {
        MemberRepository repository = mock(MemberRepository.class);
        MemberCreationTransaction creation =
                new MemberCreationTransaction(repository, new TestManager(true, false));
        MemberCreationException error =
                catchThrowableOfType(
                        MemberCreationException.class,
                        () -> creation.create(member(), "phone-hash"));
        assertThat(error.rolledBack()).isTrue();
        assertThat(error).hasNoCause().hasMessageNotContaining("test-sensitive-begin-detail");
        verifyNoInteractions(repository);
    }

    @Test
    void commitFailureIsAmbiguousAndMustRetainClaim() {
        MemberRepository repository = mock(MemberRepository.class);
        Member member = member();
        when(repository.saveAndFlush(member)).thenReturn(member);
        MemberCreationTransaction creation =
                new MemberCreationTransaction(repository, new TestManager(false, true));
        MemberCreationException error =
                catchThrowableOfType(
                        MemberCreationException.class, () -> creation.create(member, "phone-hash"));
        assertThat(error.rolledBack()).isFalse();
        assertThat(error).hasNoCause().hasMessageNotContaining("test-sensitive-commit-detail");
    }

    @Test
    void knownCallbackRollbackCanRestoreClaim() {
        MemberRepository repository = mock(MemberRepository.class);
        when(repository.existsByLoginId("transaction-test")).thenReturn(true);
        MemberCreationTransaction creation =
                new MemberCreationTransaction(repository, new TestManager(false, false));
        MemberCreationException error =
                catchThrowableOfType(
                        MemberCreationException.class,
                        () -> creation.create(member(), "phone-hash"));
        assertThat(error.rolledBack()).isTrue();
        assertThat(error.errorCode().code()).isEqualTo("MEMBER-001");
    }

    private Member member() {
        return new Member(
                "transaction-test",
                "test-hash",
                "닉네임",
                "test-envelope",
                "phone-hash",
                Instant.now(),
                true,
                "privacy-collection-v1",
                Instant.now());
    }

    /** 실제 트랜잭션 관리자의 synchronization 경로에서 begin/commit 장애만 주입한다. */
    private static class TestManager extends AbstractPlatformTransactionManager {
        private final boolean failBegin;
        private final boolean failCommit;

        TestManager(boolean failBegin, boolean failCommit) {
            this.failBegin = failBegin;
            this.failCommit = failCommit;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (failBegin) {
                throw new CannotCreateTransactionException("test-sensitive-begin-detail");
            }
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("test-sensitive-commit-detail");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 실제 DB 없는 테스트 관리자: Spring의 완료 상태 판정을 검증한다.
        }
    }
}
