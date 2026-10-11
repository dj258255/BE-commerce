package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LiveOrderService;
import com.beomsu.becommerce.live.OrderPlacement;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 고정 상품 카드 "바로 주문" REST 컨트롤러(R10·R11·R12) — 로그인 사용자 전용
 * (SecurityConfig가 ROLE_USER로 잠근다, 기존 {@code /api/v1/orders}와 같은 수준 — R5.2는
 * 비로그인 호출이 401로 거절돼야 한다).
 *
 * <p>장바구니를 거치지 않는다(R11) — 이 호출 하나가 바로 주문 생성이다. 결제는 이 응답의
 * {@code orderNo}·{@code totalAmount}로 기존 {@code POST /api/v1/payments/confirm}을
 * 그대로 호출해 진행한다(화면 쪽 책임, media는 결제에 관여하지 않는다, R32).
 *
 * <p>{@code Idempotency-Key} 헤더는 필수다(R12.2) — 같은 키로 다시 호출해도 주문은 1건만
 * 생성되고 같은 주문 id가 돌아온다.
 */
@RestController
@RequestMapping("/api/v1/live/broadcasts/{id}/orders")
public class LiveOrderController {

    private final LiveOrderService liveOrderService;

    public LiveOrderController(LiveOrderService liveOrderService) {
        this.liveOrderService = liveOrderService;
    }

    @PostMapping
    public ResponseEntity<LiveOrderResponse> order(@PathVariable long id, @RequestBody LiveOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Principal principal) {
        OrderPlacement.PlacedOrder placed =
                liveOrderService.order(userId(principal), id, request.productId(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new LiveOrderResponse(placed.orderNo(), placed.totalAmount(), placed.expiresAt()));
    }

    private static long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}
