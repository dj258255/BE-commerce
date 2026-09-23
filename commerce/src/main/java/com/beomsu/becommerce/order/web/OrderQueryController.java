package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.order.internal.OrderDetailView;
import com.beomsu.becommerce.order.internal.OrderQueryService;
import com.beomsu.becommerce.order.internal.OrderSummaryView;
import com.beomsu.becommerce.payment.PaymentDetailView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 주문·결제 조회 REST 컨트롤러 — 승인 미확정(202 UNKNOWN)을 폴링으로 확정 확인하는 진입점.
 *
 * <p>결제 조회(GET /payments/{id})도 order가 담당한다 — 소유권(userId↔주문) 검증의 기준값을 order가
 * 가지므로, 승인(POST /payments/confirm)이 order의 {@code CheckoutController}에 있는 것과 같은 결이다.
 * 조회도 principal의 userId로 소유권을 검증해 남의 주문·결제를 볼 수 없게 한다(IDOR 방지).
 */
@RestController
public class OrderQueryController {

    private final OrderQueryService orderQueryService;

    public OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    @GetMapping("/api/v1/orders")
    public ResponseEntity<List<OrderSummaryView>> myOrders(Principal principal) {
        long userId = Long.parseLong(principal.getName());
        return ResponseEntity.ok(orderQueryService.myOrders(userId));
    }

    @GetMapping("/api/v1/orders/{orderNo}")
    public ResponseEntity<OrderDetailView> getOrder(@PathVariable String orderNo, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        return ResponseEntity.ok(orderQueryService.getOrder(orderNo, userId));
    }

    @GetMapping("/api/v1/payments/{paymentId}")
    public ResponseEntity<PaymentDetailView> getPayment(@PathVariable long paymentId, Principal principal) {
        long userId = Long.parseLong(principal.getName());
        return ResponseEntity.ok(orderQueryService.getPayment(paymentId, userId));
    }
}
