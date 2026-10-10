package com.beomsu.becommerce.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * R13: 미결제 선점 수량 반환(기본 5분 타임아웃)과 결제 UNKNOWN 중 수량 유지.
 *
 * <p>Redis ZSET의 score(만료 평가 후보가 되는 시각)가 지난 홀드만 본다 — 아직 TTL 안이면
 * 건드리지 않는다(R13.1 "선점 후 5분이 지나면"). 후보마다 연결된 주문의 결제 결과를
 * commerce에 물어({@link OrderPaymentStatus}, 기존 결제 정합성 설계와 같은 상태를 읽는다)
 * 셋으로 가른다:
 * <ul>
 *   <li>{@code PAID} — 영구 확정한다(다시는 평가 후보가 되지 않게 score를 먼 미래로 미룬다).
 *       남은 수량은 이미 이 홀드를 포함해 계산돼 있었으므로 새 이벤트를 내보내지 않는다</li>
 *   <li>{@code IN_PROGRESS}(결제 결과 UNKNOWN) — 그대로 둔다(R13.2). 기존
 *       {@code PaymentRecoveryService}가 PG 조회로 확정하면({@code PaymentRecoveredEvent} →
 *       {@code PaymentRecoveredListener} → 주문 상태 확정) 다음 주기에 이 서비스가 그 결과를
 *       {@code PAID} 또는 {@code OTHER}로 다시 보게 된다 — 이 서비스는 결제 복구 자체를
 *       중복 구현하지 않는다(R32), 결과만 읽는다</li>
 *   <li>{@code OTHER}(미결제 포기·실패·취소·만료·기록 없음) — 즉시 반환한다(R13.1)</li>
 * </ul>
 * 반환이 하나라도 일어나면 그 방송의 남은 수량이 늘어난 것이므로 시청자 화면에 갱신 이벤트를
 * 즉시 내보낸다(R13.1 "화면 카드의 남은 수량도 갱신된다").
 */
@Service
public class LiveOrderHoldReconciler {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderHoldReconciler.class);

    private final LivePinRepository pinRepository;
    private final LiveOrderGate gate;
    private final OrderPaymentStatus orderPaymentStatus;
    private final LivePinBroadcaster broadcaster;
    private final Clock clock;

    @Autowired
    public LiveOrderHoldReconciler(LivePinRepository pinRepository, LiveOrderGate gate,
            OrderPaymentStatus orderPaymentStatus, LivePinBroadcaster broadcaster) {
        this(pinRepository, gate, orderPaymentStatus, broadcaster, Clock.systemUTC());
    }

    LiveOrderHoldReconciler(LivePinRepository pinRepository, LiveOrderGate gate,
            OrderPaymentStatus orderPaymentStatus, LivePinBroadcaster broadcaster, Clock clock) {
        this.pinRepository = pinRepository;
        this.gate = gate;
        this.orderPaymentStatus = orderPaymentStatus;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    /** 지금 고정 중인 모든 방송의 만료 지난 홀드를 평가한다. 반환값은 실제로 돌려준(release) 건수. */
    public int reconcileAll() {
        List<LivePin> pinned = pinRepository.findByProductIdIsNotNull();
        int released = 0;
        for (LivePin pin : pinned) {
            released += reconcileOne(pin);
        }
        return released;
    }

    private int reconcileOne(LivePin pin) {
        long broadcastId = pin.getBroadcastId();
        long generation = pin.getGeneration();
        long productId = pin.getProductId();
        long nowMs = clock.millis();

        List<String> candidates = gate.expiredHolds(broadcastId, generation, nowMs);
        int released = 0;
        for (String member : candidates) {
            String orderNo = gate.orderNoOf(broadcastId, generation, member);
            OrderPaymentStatus.Outcome outcome =
                    orderNo == null ? OrderPaymentStatus.Outcome.OTHER : orderPaymentStatus.outcomeOf(orderNo);
            switch (outcome) {
                case PAID -> gate.confirmPermanently(broadcastId, generation, member);
                case IN_PROGRESS -> log.debug(
                        "선점 유지(결제 결과 UNKNOWN) broadcastId={} generation={} orderNo={}",
                        broadcastId, generation, orderNo);
                case OTHER -> {
                    gate.release(broadcastId, generation, member);
                    released++;
                    log.info("미결제 선점 반환 broadcastId={} generation={} orderNo={} idempotencyKey={}",
                            broadcastId, generation, orderNo, member);
                }
            }
        }
        if (released > 0) {
            int remaining = Math.max(0, pin.getLimitedQuantity() - gate.currentCount(broadcastId, generation));
            broadcaster.broadcast(
                    LivePinEventView.quantityChanged(broadcastId, productId, pin.getSeq(), remaining, clock.instant()));
        }
        return released;
    }
}
