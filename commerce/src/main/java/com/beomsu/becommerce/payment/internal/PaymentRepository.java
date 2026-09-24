package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentKey(String paymentKey);

    /** SLO 게이지용 — 가장 오래된 UNKNOWN(미확정) 결제의 요청 시각. 미확정이 없으면 empty. */
    @Query("select min(p.requestedAt) from Payment p where p.status = com.beomsu.becommerce.payment.PaymentStatus.UNKNOWN")
    Optional<Instant> findOldestUnknownRequestedAt();

    /** 복구 배치용: 특정 상태로 일정 시각 이전부터 머문 결제(미확정 방치 건). 정렬을 걸지 않는다({@code unordered}). */
    List<Payment> findByStatusAndRequestedAtBefore(PaymentStatus status, Instant threshold, Pageable page);

    /** 복구 배치 {@code oldest}: 같은 조건에 오래된 순을 명시한다(#248). */
    List<Payment> findByStatusAndRequestedAtBeforeOrderByRequestedAtAsc(PaymentStatus status, Instant threshold, Pageable page);

    /** 복구 배치 {@code backoff}: 다시 물어볼 때가 된 것만, 오래된 순(#248). */
    @Query("select p from Payment p where p.status = com.beomsu.becommerce.payment.PaymentStatus.UNKNOWN"
            + " and p.requestedAt < :threshold and (p.recoveryNextAt is null or p.recoveryNextAt <= :now)"
            + " order by p.requestedAt")
    List<Payment> findRecoverableUnknown(@Param("threshold") Instant threshold, @Param("now") Instant now, Pageable page);

    /** 어드민 관측용 — 상태별 결제 페이지(운영이 UNKNOWN 미확정 건을 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /** 취소용: 주문의 성공한(취소 가능한) 결제 1건. 재시도로 ABORTED 등이 섞여 있어도 성공 건만 고른다. */
    Optional<Payment> findFirstByOrderNoAndStatusIn(String orderNo, List<PaymentStatus> statuses);

    /** 조회용: 주문의 최신 결제 시도 1건. 재시도로 여러 건이 있어도 현재 상태를 대표한다. */
    Optional<Payment> findFirstByOrderNoOrderByRequestedAtDesc(String orderNo);

    /** 그 주문에 결제가 <아예> 있었는지. "이미 취소됨"과 "없음"을 가르는 데 쓴다. */
    boolean existsByOrderNo(String orderNo);
}
