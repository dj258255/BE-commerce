package com.beomsu.becommerce.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 고정 상품 카드 "바로 주문" 애플리케이션 서비스(R10·R11·R12·R14, ADR-085) — live 모듈의 공개
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
 *       낭비하지 않고 다음 요청이 쓸 수 있게). 성공하면 이 홀드가 어느 주문인지 기록하고
 *       (R13이 나중에 결제 상태를 물을 수 있게), 갱신된 남은 수량을 즉시 시청자 화면에
 *       내보낸다(R13.1·R14.1) — 0이 되면 그게 곧 매진 신호다({@link LivePinEventType#QUANTITY_CHANGED}).</li>
 * </ol>
 */
@Service
public class LiveOrderService {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderService.class);

    private final LivePinRepository pinRepository;
    private final OrderPlacement orderPlacement;
    private final LiveOrderGate gate;
    private final LivePinBroadcaster broadcaster;
    private final Clock clock;

    @Autowired
    public LiveOrderService(LivePinRepository pinRepository, OrderPlacement orderPlacement, LiveOrderGate gate,
            LivePinBroadcaster broadcaster) {
        this(pinRepository, orderPlacement, gate, broadcaster, Clock.systemUTC());
    }

    LiveOrderService(LivePinRepository pinRepository, OrderPlacement orderPlacement, LiveOrderGate gate,
            LivePinBroadcaster broadcaster, Clock clock) {
        this.pinRepository = pinRepository;
        this.orderPlacement = orderPlacement;
        this.gate = gate;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    public OrderPlacement.PlacedOrder order(long userId, long broadcastId, long productId, String idempotencyKey) {
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .filter(LivePin::isPinned)
                .filter(p -> p.getProductId() == productId)
                .orElseThrow(() -> LiveOrderException.pinMismatch(broadcastId, productId));

        long generation = pin.getGeneration();
        int limit = pin.getLimitedQuantity();
        if (!gate.tryReserve(broadcastId, generation, limit, idempotencyKey)) {
            throw LiveOrderException.limitedQuantitySoldOut(broadcastId, productId);
        }
        OrderPlacement.PlacedOrder placed;
        try {
            placed = orderPlacement.place(userId, productId, pin.getPrice(), idempotencyKey);
        } catch (RuntimeException e) {
            // 선점만 됐고 주문은 안 만들어졌다 — 되돌려야 다음 요청이 이 슬롯을 다시 쓸 수 있다.
            gate.release(broadcastId, generation, idempotencyKey);
            throw e;
        }
        // 주문은 이미 성공했다(commerce가 커밋했다) — 여기서부터는 "선점 기록·방송"일 뿐이고,
        // 이게 실패해도 절대 release 하면 안 된다. release 하면 Redis는 자리가 비었다고 보고
        // 다른 요청에게 그 슬롯을 다시 내주는데, MySQL에는 이미 진짜 주문이 있다 — 한도 N을
        // 넘기는 결과(ADR-085 "R13: 어긋남과 처리" 절 참고)로 이어진다. 실패하면 로그만 남기고
        // 주문은 그대로 돌려준다 — 고객은 주문을 잃지 않는다.
        try {
            gate.recordOrder(broadcastId, generation, idempotencyKey, placed.orderNo());
            announceRemaining(pin, broadcastId, productId, generation, limit);
        } catch (RuntimeException e) {
            log.warn("주문({})은 성공했지만 선점 기록·방송에 실패했습니다 — R13 반환 판정이 이 "
                            + "주문을 못 찾을 수 있습니다(ADR-085 다시 볼 조건). broadcastId={} idempotencyKey={}",
                    placed.orderNo(), broadcastId, idempotencyKey, e);
        }
        return placed;
    }

    /** R13.1·R14.1: 지금 남은 수량을 즉시 방송한다. 0이면 매진 — 운영 로그에도 남긴다(R14.2·R31.1). */
    private void announceRemaining(LivePin pin, long broadcastId, long productId, long generation, int limit) {
        int remaining = Math.max(0, limit - gate.currentCount(broadcastId, generation));
        broadcaster.broadcast(
                LivePinEventView.quantityChanged(broadcastId, productId, pin.getSeq(), remaining, clock.instant()));
        if (remaining == 0) {
            log.info("한정 수량 매진 broadcastId={} productId={} at={}", broadcastId, productId, clock.instant());
        }
    }
}
