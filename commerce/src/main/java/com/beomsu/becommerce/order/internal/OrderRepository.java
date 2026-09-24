package com.beomsu.becommerce.order.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 사용자가 산 상품(결제 완료 주문의 항목), 최근 것부터(#254). 주문 id 역순 → 항목 id 역순이다.
     * {@code (user_id, id)} 인덱스로 사용자의 주문만 읽는다.
     */
    @org.springframework.data.jpa.repository.Query("select i.productId from OrderItem i join i.order o"
            + " where o.userId = :userId and o.status = com.beomsu.becommerce.order.internal.OrderStatus.PAID"
            + " order by o.id desc, i.id desc")
    List<Long> findPurchasedProductIds(@org.springframework.data.repository.query.Param("userId") long userId, Pageable page);

    /** 내 주문 목록 — 최신순 최근 50건. Top50으로 DB에서 상한을 걸어 무한 적재를 막는다. */
    List<Order> findTop50ByUserIdOrderByIdDesc(long userId);

    /** 만료 배치용: 특정 상태이면서 만료 예정 시각이 지난 주문(결제 미완료로 방치된 건). */
    List<Order> findByStatusAndExpiresAtBefore(OrderStatus status, Instant now, Pageable page);

    /** 멈춘 사가 복구용: 특정 상태로 이 시각 이전부터 머물러 있는 주문(마지막 갱신 기준). */
    /**
     * 멈춘 체크아웃을 고른다. <b>오래 머문 것부터</b> 준다.
     *
     * <p>정렬이 없으면 순서가 DB 마음이라, 실패가 반복되는 건이 앞자리를 계속 잡을 수 있다.
     * 오래된 순으로 주고 실패한 건은 {@code markRecoveryAttempted} 로 뒤로 보낸다.
     */
    List<Order> findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            OrderStatus status, Instant threshold, Pageable page);
}
