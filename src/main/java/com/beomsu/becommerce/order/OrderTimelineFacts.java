package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.Order;
import java.time.Instant;
import java.util.Optional;

/**
 * 주문 도메인이 <b>타임라인에 내주는 사실</b> (ADR-011).
 *
 * <p><b>왜 이 클래스가 있나</b>: 조립기가 남의 저장소를 직접 열지 못하게 하려고 있다.
 * {@code OrderRepository}는 package-private이고 앞으로도 그래야 한다 — 한 번 public이 되면
 * 조회뿐 아니라 {@code save()}도 열려 도메인 규칙을 우회하는 뒷문이 된다.
 *
 * <p>대신 이 도메인이 <b>무엇을 남에게 보여줄지 스스로 정해</b> 읽기 전용으로만 노출한다.
 * 엔티티를 그대로 내주지 않는 것도 같은 이유다 — {@link Order}를 넘기면 받는 쪽이
 * 상태 전이 메서드({@code markPaid()} 등)를 부를 수 있다.
 */
@org.springframework.stereotype.Service
public class OrderTimelineFacts {

    private final OrderRepository orderRepository;

    OrderTimelineFacts(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 주문의 뼈대 사실. 없으면 {@code empty} — 주문이 없는 것은 오류가 아니라
     * "그런 주문 없음"이라는 사실이고, 대사에서는 실제로 일어난다(EXTERNAL_ONLY).
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Optional<OrderFacts> findByOrderNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .map(o -> new OrderFacts(
                        o.getOrderNo(),
                        o.getStatus().name(),
                        o.getTotalAmount(),
                        o.getItems().size(),
                        o.getCreatedAt(),
                        o.getUpdatedAt()));
    }

    /**
     * 타임라인이 필요로 하는 것만 담은 읽기 전용 뷰.
     *
     * <p>{@code createdAt}과 {@code updatedAt}만 있고 중간 전이 이력은 없다 —
     * 주문은 상태를 덮어쓰기 때문이다. 전이 이력이 필요하면 결제 이력이 대신 답한다
     * (payment_history는 append-only).
     */
    public record OrderFacts(String orderNo, String status, long totalAmount,
                             int itemCount, Instant createdAt, Instant updatedAt) {
    }
}
