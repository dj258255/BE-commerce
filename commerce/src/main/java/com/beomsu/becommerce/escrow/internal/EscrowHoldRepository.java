package com.beomsu.becommerce.escrow.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface EscrowHoldRepository extends JpaRepository<EscrowHold, Long> {

    /** 주문당 1홀드 — orderNo로 조회. */
    Optional<EscrowHold> findByOrderNo(String orderNo);

    /** 자동 구매확정 도래분 — 주어진 상태이면서 autoReleaseAt이 임계 시각 이전인 홀드. */
    List<EscrowHold> findByStatusAndAutoReleaseAtBefore(EscrowStatus status, Instant threshold, Pageable page);
}
