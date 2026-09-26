package com.beomsu.becommerce.order.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 재고 예약(#374). 상태 전이는 조건부 UPDATE 라 같은 전이를 두 번 해도 한 번만 성공한다. */
interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderNo(String orderNo);

    /** 이 주문의 RESERVED 를 모두 CLAIMED 로. 재고는 예약 때 이미 빠져 있다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update StockReservation r set r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.CLAIMED,
                   r.updatedAt = :now
             where r.orderNo = :orderNo
               and r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.RESERVED
            """)
    int claimReserved(@Param("orderNo") String orderNo, @Param("now") Instant now);

    /** RESERVED 한 줄을 RELEASED 로. 1 이면 이 호출이 되돌릴 차례를 얻었다(재고를 더해야 한다). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update StockReservation r set r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.RELEASED,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.RESERVED
            """)
    int markReleased(@Param("id") Long id, @Param("now") Instant now);

    /** RELEASED 한 줄을 다시 RESERVED 로(거절 뒤 같은 주문의 다시 시도). 재고는 호출자가 먼저 뺀다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update StockReservation r set r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.RESERVED,
                   r.updatedAt = :now
             where r.id = :id
               and r.status = com.beomsu.becommerce.order.catalog.StockReservation.Status.RELEASED
            """)
    int reReserve(@Param("id") Long id, @Param("now") Instant now);
}
