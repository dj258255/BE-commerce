package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.order.internal.PurchaseConfirmationResult;
import com.beomsu.becommerce.order.internal.PurchaseConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 구매확정 REST 컨트롤러 — 에스크로 릴리스의 구매자용 진입점.
 *
 * <p>구매자가 수령을 확정하면 보류(HELD)된 결제금이 정산 가능(RELEASED) 상태로 전이한다. 구매확정은
 * 구매자 본인만 할 수 있어야 하므로(IDOR 방지), 서비스가 principal의 userId로 주문 소유권을 검증한
 * 뒤에만 릴리스한다. 릴리스는 상태 기준 멱등이라 중복 요청도 안전해 별도 멱등키는 두지 않는다.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class PurchaseConfirmationController {

    private final PurchaseConfirmationService purchaseConfirmationService;

    public PurchaseConfirmationController(PurchaseConfirmationService purchaseConfirmationService) {
        this.purchaseConfirmationService = purchaseConfirmationService;
    }

    @PostMapping("/{orderNo}/confirm-purchase")
    public ResponseEntity<PurchaseConfirmationResult> confirmPurchase(
            @PathVariable String orderNo,
            Principal principal) {

        // 인증된 사용자 ID — 주문 소유권 검증에 쓴다(남의 주문 구매확정/조기 정산 방지).
        long userId = Long.parseLong(principal.getName());
        PurchaseConfirmationResult result = purchaseConfirmationService.confirmPurchase(orderNo, userId);
        return ResponseEntity.ok(result);
    }
}
