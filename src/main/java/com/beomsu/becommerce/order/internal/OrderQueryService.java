package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.PaymentDetailView;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문·결제 조회 오케스트레이션 애플리케이션 서비스 — order 모듈의 조회 진입점.
 *
 * <p>202 UNKNOWN으로 응답된 승인의 확정을 클라이언트가 폴링으로 확인하는 계약(10-API-스펙 §2, §7)을
 * 완성하는 조회 경로다. 조회도 남의 주문·결제를 볼 수 없어야 하므로(IDOR 방지), 두 조회 모두
 * <b>소유권을 order가 검증</b>한다 — 결제↔주문 매핑(userId↔주문)은 order가 소유하기 때문이다.
 * 결제 상세는 payment 모듈에서 뷰 record로 받아 넘길 뿐, 엔티티를 경계 밖으로 노출하지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    /**
     * 주문 상세 조회(결제 상태 포함). 주문 로드 → <b>소유권 검증</b> → 대표 결제 상태 조회 순.
     * 소유권 위반이면 결제 상태를 조회하기 전에 예외가 나 남의 결제 존재조차 드러내지 않는다.
     */
    public OrderDetailView getOrder(String orderNo, long authenticatedUserId) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));
        order.verifyOwner(authenticatedUserId);

        String paymentStatus = paymentService.paymentStatusByOrderNo(orderNo).orElse(null);
        List<OrderItemView> items = order.getItems().stream()
                .map(i -> new OrderItemView(i.getProductId(), i.getProductName(),
                        i.getUnitPrice(), i.getQuantity()))
                .toList();
        return new OrderDetailView(order.getOrderNo(), order.getStatus().name(),
                order.getTotalAmount(), items, paymentStatus,
                order.getExpiresAt(), order.getCreatedAt());
    }

    /**
     * 내 주문 목록 — 최신순 최근 50건. userId는 인증 principal에서 온 값이라, 다른 사용자의 주문은
     * 애초에 조회 대상이 아니다(쿼리 자체가 본인 것만 가져옴 — IDOR 방지의 가장 단순한 형태).
     */
    public List<OrderSummaryView> myOrders(long authenticatedUserId) {
        return orderRepository.findTop50ByUserIdOrderByIdDesc(authenticatedUserId).stream()
                .map(OrderSummaryView::from)
                .toList();
    }

    /**
     * 결제 상세 조회. 결제→주문 매핑을 payment에서 받아 그 주문의 소유권을 검증한 뒤에만 상세를 반환한다
     * (남의 결제 조회 차단). 결제가 없으면 404, 소유권 위반이면 403.
     */
    public PaymentDetailView getPayment(long paymentId, long authenticatedUserId) {
        String orderNo = paymentService.orderNoOf(paymentId)
                .orElseThrow(() -> paymentNotFound(paymentId));
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));
        order.verifyOwner(authenticatedUserId);
        return paymentService.getDetail(paymentId)
                .orElseThrow(() -> paymentNotFound(paymentId));
    }

    private static PaymentException paymentNotFound(long paymentId) {
        return new PaymentException("PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다: " + paymentId);
    }
}
