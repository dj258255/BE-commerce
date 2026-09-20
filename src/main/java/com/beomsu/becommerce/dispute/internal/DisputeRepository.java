package com.beomsu.becommerce.dispute.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    /** 멱등 개시 판정 — 이미 받은 차지백인지. */
    boolean existsByChargebackId(String chargebackId);

    Optional<Dispute> findByChargebackId(String chargebackId);

    /** 최근 분쟁 목록 — 어드민 감사용. Top50으로 상한. */
    List<Dispute> findTop50ByOrderByIdDesc();

    /** 타임라인 조립용(ADR-011). 한 주문에 분쟁이 여러 번 걸릴 수 있어 목록이다. */
    List<Dispute> findByOrderNoOrderByIdAsc(String orderNo);

    /**
     * 승패가 갈린 분쟁을 최근 것부터. <b>이상거래 라벨의 원재료다.</b>
     *
     * <p>상한을 받는다. 상황 2.3 에서 배치 조회에 상한을 건 것과 같은 이유다.
     */
    List<Dispute> findByStatusInAndResolvedAtGreaterThanEqualOrderByResolvedAtDesc(
            java.util.Collection<DisputeStatus> statuses, java.time.Instant since,
            org.springframework.data.domain.Pageable pageable);

    /** 아직 다투지 않은 건. 기한이 살아 있는 동안에만 대응할 수 있다. */
    List<Dispute> findByStatus(DisputeStatus status);
}
