package com.beomsu.pay.notification.consumption;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 의
// allowedDependencies 가 막는다.
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    // findAll(), findById(), delete(), count() 는 JpaRepository가 제공 — 어드민 조회·재처리에 사용

    /**
     * 가장 오래 기다린 격리 건의 생성 시각. 건수만으로는 적체를 못 본다 —
     * 방금 쌓인 열 건과 이틀 묵은 한 건은 위험이 다르다.
     */
    @Query("select min(d.createdAt) from DeadLetter d")
    Optional<Instant> findOldestCreatedAt();
}
