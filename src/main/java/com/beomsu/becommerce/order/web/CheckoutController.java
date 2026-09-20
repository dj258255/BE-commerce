package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.payment.web.PgSelectionController;
import com.beomsu.becommerce.order.internal.CheckoutResult;
import com.beomsu.becommerce.order.internal.CheckoutService;
import com.beomsu.becommerce.order.idempotency.IdempotencyService;
import com.beomsu.becommerce.shared.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 결제 승인(체크아웃) REST 컨트롤러.
 *
 * <p>프론트가 successUrl로 받은 파라미터를 그대로 전달하면, 서버가 금액 검증 후 PG 승인을 호출한다.
 * 응답 상태코드로 승인 결과를 구분한다: 승인 완료 200 / 미확정(UNKNOWN) 202 / 거절 400 (10-API-스펙).
 *
 * <p>모든 승인 요청은 {@code Idempotency-Key} 헤더로 멱등 처리된다 — "따닥" 중복결제와
 * 타임아웃 후 재시도를 안전하게 만든다.
 *
 * <p>어느 PG로 갈지는 <b>결제창을 띄우기 전에</b> {@code PgSelectionController}가 정한다
 * (payment 모듈 소유). 승인 단계에서는 PG를 넘길 수 없기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class CheckoutController {

    private static final String PATH = "/api/v1/payments/confirm";

    private final CheckoutService checkoutService;
    private final IdempotencyService idempotencyService;

    public CheckoutController(CheckoutService checkoutService, IdempotencyService idempotencyService) {
        this.checkoutService = checkoutService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/confirm")
    public ResponseEntity<CheckoutResult> confirm(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfirmRequest request,
            Principal principal) {

        // 인증된 사용자 ID — 주문 소유권 검증에 쓴다(남의 주문 결제/포인트 소진 방지).
        long userId = Long.parseLong(principal.getName());
        CheckoutResult result = idempotencyService.execute(
                idempotencyKey, PATH, "POST", request, CheckoutResult.class,
                () -> checkoutService.confirm(
                        request.orderNo(), request.paymentKey(),
                        Money.krw(request.amount()), request.pointAmount(), request.walletAmount(),
                        userId, request.installmentMonths()));

        HttpStatus status = switch (result.paymentStatus()) {
            case DONE -> HttpStatus.OK;               // 승인 완료
            case UNKNOWN -> HttpStatus.ACCEPTED;      // 미확정 → 폴링 안내
            default -> HttpStatus.BAD_REQUEST;        // 거절(ABORTED) 등
        };
        return ResponseEntity.status(status).body(result);
    }

    /**
     * 결제 승인 요청.
     *
     * @param amount       카드로 결제할 금액(복합결제 재정의: 카드 몫)
     * @param pointAmount  포인트로 결제할 금액. JSON에 없으면 0(순수 카드결제 = 기존 동작)
     * @param walletAmount 선불 월렛으로 결제할 금액. JSON에 없으면 0. 카드+포인트+월렛 == 주문 총액이어야 한다.
     * @param installmentMonths 할부 개월. JSON에 없으면 0(일시불). 카드 몫이 5만원 미만이면 거절한다
     */
    public record ConfirmRequest(String paymentKey, String orderNo, long amount,
                                 long pointAmount, long walletAmount, int installmentMonths) {
    }
}
