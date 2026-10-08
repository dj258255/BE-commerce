package com.beomsu.becommerce.live;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 고정 상품 카드 "바로 주문" 애플리케이션 서비스(R10·R11·R12, ADR-085) — live 모듈의 공개
 * 진입점. 클라이언트는 {@code productId}만 보낸다(지금 보고 있는 카드가 어떤 상품인지의
 * 확인용) — 가격·수량은 전혀 받지 않는다(R10: 가능 여부·금액은 항상 서버 상태로 판정). 수량은
 * 늘 1이다(한정판 1인 1건 — 그래야 R12의 Redis 게이트가 "카드(ZSET 멤버) 수 ≤ N"만으로
 * 정확히 한정 수량을 강제할 수 있다, ADR-085).
 *
 * <ol>
 *   <li>지금 고정된 상품이 요청과 같은지 확인(R10.2) — 다르거나 없으면 409로 거절, 주문을
 *       만들지 않는다</li>
 *   <li>Redis 한정 수량 게이트(R12) — 실패하면 commerce 호출 없이 즉시 거절한다(R15 방향:
 *       거절 경로에서 결제·주문 생성 호출이 전혀 없어야 빠르다). 게이트 키는 {@code
 *       broadcastId}뿐 아니라 지금 고정의 {@code generation}(세대)으로도 갈린다 — 같은
 *       방송에서 다시 고정(완판 후 재고정·다른 상품으로 교체)해도 이전 드롭의 선점이 섞여
 *       들어오지 않는다(ADR-085)</li>
 *   <li>commerce 주문 생성({@link OrderPlacement}, R11) — 실패하면 선점을 돌려준다(그 슬롯을
 *       낭비하지 않고 다음 요청이 쓸 수 있게)</li>
 * </ol>
 */
@Service
public class LiveOrderService {

    private final LivePinRepository pinRepository;
    private final OrderPlacement orderPlacement;
    private final LiveOrderGate gate;

    @Autowired
    public LiveOrderService(LivePinRepository pinRepository, OrderPlacement orderPlacement, LiveOrderGate gate) {
        this.pinRepository = pinRepository;
        this.orderPlacement = orderPlacement;
        this.gate = gate;
    }

    public OrderPlacement.PlacedOrder order(long userId, long broadcastId, long productId, String idempotencyKey) {
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .filter(LivePin::isPinned)
                .filter(p -> p.getProductId() == productId)
                .orElseThrow(() -> LiveOrderException.pinMismatch(broadcastId, productId));

        long generation = pin.getGeneration();
        if (!gate.tryReserve(broadcastId, generation, pin.getLimitedQuantity(), idempotencyKey)) {
            throw LiveOrderException.limitedQuantitySoldOut(broadcastId, productId);
        }
        try {
            return orderPlacement.place(userId, productId, pin.getPrice(), idempotencyKey);
        } catch (RuntimeException e) {
            gate.release(broadcastId, generation, idempotencyKey);
            throw e;
        }
    }
}
