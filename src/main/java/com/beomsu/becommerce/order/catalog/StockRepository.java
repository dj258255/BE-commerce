package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface StockRepository extends JpaRepository<Stock, Long> {

    /** 여러 상품의 재고를 한 번에 — 목록 화면의 품절 표시용(N+1 방지). */
    List<Stock> findByProductIdIn(Collection<Long> productIds);

    /** 비관적 락 — SELECT ... FOR UPDATE. 충돌이 잦은 재고 차감에 쓴다(Phase 5 비교 실험). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.productId = :id")
    Optional<Stock> findByIdForUpdate(@Param("id") Long id);

    /**
     * 조건부 차감 — 락 없이 원자적 UPDATE. quantity >= qty일 때만 차감되며, 영향 행이 0이면 재고 부족.
     * 애플리케이션 락·재시도 없이 DB 한 번으로 정합성을 지키는 가장 저비용 방식.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = s.quantity - :qty "
            + "where s.productId = :id and s.quantity >= :qty")
    int deductConditionally(@Param("id") Long id, @Param("qty") int qty);

    /** 재고 가산 — 전액 취소 시 차감했던 수량을 되돌린다. 조건 없이 원자적 UPDATE. */
    @Modifying(clearAutomatically = true)
    @Query("update Stock s set s.quantity = s.quantity + :qty where s.productId = :id")
    int restore(@Param("id") Long id, @Param("qty") int qty);
}
