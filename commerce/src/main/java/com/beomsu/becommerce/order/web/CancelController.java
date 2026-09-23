package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.order.internal.CancelResult;
import com.beomsu.becommerce.order.internal.CancelService;
import com.beomsu.becommerce.order.idempotency.IdempotencyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 주문 취소 REST 컨트롤러.
 *
 * <p>취소도 승인과 동일하게 {@code Idempotency-Key} 헤더로 멱등 처리한다 — "따닥" 중복 취소와
 * 타임아웃 후 재시도를 안전하게 만든다. 취소 대상 주문의 소유권은 서비스가 principal의 userId로 검증한다.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class CancelController {

    private static final String PATH = "/api/v1/orders/cancel";

    private final CancelService cancelService;
    private final IdempotencyService idempotencyService;

    public CancelController(CancelService cancelService, IdempotencyService idempotencyService) {
        this.cancelService = cancelService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/{orderNo}/cancel")
    public ResponseEntity<CancelResult> cancel(
            @PathVariable String orderNo,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CancelRequest request,
            Principal principal) {

        // 인증된 사용자 ID — 주문 소유권 검증에 쓴다(남의 주문 취소/환불 방지).
        long userId = Long.parseLong(principal.getName());
        CancelResult result = idempotencyService.execute(
                idempotencyKey, PATH, "POST", request, CancelResult.class,
                () -> cancelService.cancel(orderNo, request.cancelAmount(), request.reason(), userId));

        return ResponseEntity.ok(result);
    }

    /**
     * 주문 취소 요청.
     *
     * @param cancelAmount 취소할 금액(전액/부분)
     * @param reason       취소 사유
     */
    public record CancelRequest(long cancelAmount, String reason) {
    }
}
