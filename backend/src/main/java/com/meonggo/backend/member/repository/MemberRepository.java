package com.meonggo.backend.member.repository;

import com.meonggo.backend.member.entity.Member;
import com.meonggo.backend.member.entity.MemberStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByLoginId(String loginId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findLockedById(@Param("id") long id);

    boolean existsByLoginId(String loginId);

    // 탈퇴 후 파기 대기 중인 번호도 가입을 차단한다.
    boolean existsByPhoneLookupHash(String phoneLookupHash);

    // 계정 찾기(A9) — 활성 회원만. 활성 회원의 phone_lookup_hash 는 부분 유일 인덱스로 하나다.
    Optional<Member> findByPhoneLookupHashAndStatus(String phoneLookupHash, MemberStatus status);
}
