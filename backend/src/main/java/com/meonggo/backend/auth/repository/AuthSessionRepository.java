package com.meonggo.backend.auth.repository;

import com.meonggo.backend.auth.entity.AuthSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {
    @Query("select s.memberId from AuthSession s where s.refreshTokenSelector = :selector")
    Optional<Long> findMemberIdBySelector(@Param("selector") String selector);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.refreshTokenSelector = :selector")
    Optional<AuthSession> findLockedBySelector(@Param("selector") String selector);

    @Modifying(flushAutomatically = true)
    @Query(
            "update AuthSession s set s.revokedAt = :now where s.memberId = :memberId and s.revokedAt is null")
    int revokeAll(@Param("memberId") long memberId, @Param("now") Instant now);

    /** 비밀번호 변경 뒤 다른 기기 세션만 폐기한다 — 변경을 수행한 세션은 남긴다. */
    @Modifying
    @Query(
            "update AuthSession s set s.revokedAt = :now where s.memberId = :memberId and s.id <> :keepSessionId and s.revokedAt is null")
    int revokeAllExcept(
            @Param("memberId") long memberId,
            @Param("keepSessionId") long keepSessionId,
            @Param("now") Instant now);

    @Modifying
    @Query(
            "update AuthSession s set s.pushInstallationId = null, s.pushPlatform = null, s.pushTokenCiphertext = null, s.pushTokenLookupHash = null, s.pushLastSeenAt = null where s.memberId = :memberId")
    int clearPushRegistrations(@Param("memberId") long memberId);
}
