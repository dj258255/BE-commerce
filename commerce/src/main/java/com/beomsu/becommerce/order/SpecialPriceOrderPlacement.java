package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.idempotency.IdempotencyService;
import com.beomsu.becommerce.order.internal.CheckoutService;
import com.beomsu.becommerce.order.internal.CreateOrderResult;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 방송 특가 주문(R10·R11)을 다른 모듈(live)에 내주는 공개 진입점(ADR-085) — {@link ProductCatalogFacts}와
 * 같은 이유(order.internal을 직접 참조하면 ModularityTests가 경계 침투로 잡는다)이지만, 이건
 * 읽기가 아니라 <b>쓰기</b>(주문 생성)다. "무엇을 내줄지는 order가 스스로 정한다"는 원칙은
 * 같다 — 가격·수량을 검증 없이 그대로 받지 않는다. 상품 실존 확인과 카탈로그 재고 보호
 * ({@code StockReservationService})는 {@link CheckoutService#createSpecialPriceOrder}가
 * order 내부 규칙 그대로 수행한다 — 이 파사드는 그 호출을 멱등으로 감싸는 것 말고는 아무
 * 주문·재고 로직도 더하지 않는다.
 *
 * <p>멱등(R12.2)은 여기서 {@link IdempotencyService}로 감싼다 — {@code CheckoutController}의
 * {@code /payments/confirm}이 쓰는 것과 같은 기존 멱등 primitive를 그대로 재사용한다. media는
 * 이 서비스를 직접 호출할 수 없으므로(모듈 경계) 이 멱등 보장은 전적으로 order 모듈 내부에
 * 머문다 — media 쪽에 중복 구현하지 않는다(R32).
 */
@Service
public class SpecialPriceOrderPlacement {

    private static final String API_PATH = "/internal/live-order";
    private static final String HTTP_METHOD = "POST";

    private final CheckoutService checkoutService;
    private final IdempotencyService idempotencyService;

    SpecialPriceOrderPlacement(CheckoutService checkoutService, IdempotencyService idempotencyService) {
        this.checkoutService = checkoutService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * @param unitPrice      호출자(live)가 지금 고정된 방송 특가로 이미 확인한 가격(R10) — 그대로
     *                       주문 금액에 쓰인다
     * @param idempotencyKey 같은 키로 다시 불러도 주문이 두 번 생기지 않는다(R12.2). 키는 같은데
     *                       productId·unitPrice가 다르면 {@code IDEMPOTENCY_KEY_REUSED}(422)로
     *                       거절된다 — 키 재사용 오남용을 막는 기존 규칙 그대로다
     */
    public PlacedOrder place(long userId, long productId, long unitPrice, String idempotencyKey) {
        Request requestBody = new Request(userId, productId, unitPrice);
        CreateOrderResult result = idempotencyService.execute(idempotencyKey, API_PATH, HTTP_METHOD,
                requestBody, CreateOrderResult.class,
                () -> checkoutService.createSpecialPriceOrder(userId, productId, unitPrice));
        return new PlacedOrder(result.orderNo(), result.totalAmount(), result.expiresAt());
    }

    private record Request(long userId, long productId, long unitPrice) {
    }

    public record PlacedOrder(String orderNo, long totalAmount, Instant expiresAt) {
    }
}
