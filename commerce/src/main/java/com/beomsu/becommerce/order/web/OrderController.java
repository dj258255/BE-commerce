package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.order.internal.CheckoutService;
import com.beomsu.becommerce.order.internal.CreateOrderResult;
import com.beomsu.becommerce.order.internal.OrderLine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/** 주문 생성 REST 컨트롤러. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CheckoutService checkoutService;

    public OrderController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping
    public ResponseEntity<CreateOrderResult> create(
            @RequestBody CreateOrderRequest request, Principal principal) {
        // userId는 요청 본문이 아니라 인증된 principal에서 얻는다(스푸핑 방지).
        long userId = Long.parseLong(principal.getName());
        CreateOrderResult result = checkoutService.createOrder(userId, request.items());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** 주문 생성 요청. 클라이언트는 productId·quantity만 보내고, 가격·소유자는 서버가 정한다. */
    public record CreateOrderRequest(List<OrderLine> items) {
    }
}
