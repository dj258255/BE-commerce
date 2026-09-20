package com.beomsu.becommerce.member;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 회원 저장소. 로그인(이메일 조회)·가입 중복검사(email 존재 확인)에 쓰인다. */
public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * 아직 현재 알고리즘으로 옮겨지지 않은 해시의 수 (ADR-009).
     *
     * <p>이관은 <b>로그인한 사람만</b> 된다. 원문 비밀번호가 그때만 존재하기 때문이다. 그래서 이 값이
     * 0이 되기 전에는 이관이 끝난 게 아니고, <b>0이 되어야 레거시 인코더를 제거할 수 있다.</b>
     * 완료 판정 기준을 사람의 기억이 아니라 지표로 둔다.
     */
    @Query("select count(m) from Member m where m.passwordHash not like concat('{', :encodingId, '}%')")
    long countHashesNotEncodedWith(@Param("encodingId") String encodingId);
}
