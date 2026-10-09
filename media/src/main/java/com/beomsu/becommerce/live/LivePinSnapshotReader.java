package com.beomsu.becommerce.live;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 지금 고정 상태를 읜는 쪽(R9.2) — {@code LivePinWebSocketHandler}가 시청자 접속(최초 입장
 * 또는 재연결)마다 부른다.
 *
 * <p>{@link LivePinService}와 별도 컴포넌트로 뗀 이유: 핸들러가 접속 시 스냅샷을 보내려면
 * 이 조회가 필요하고, {@link LivePinService}는 이벤트를 내보내려 핸들러(
 * {@link LivePinBroadcaster})를 필요로 한다 — 둘을 그대로 서로 주입하면 순환 의존이 된다.
 * 읜기 전용 조회만 따로 떼어 둘 다 이것만 보게 하면 순환이 생기지 않는다.
 */
@Component
class LivePinSnapshotReader {

    private final LivePinRepository repository;
    private final ProductLookup productLookup;
    private final LiveOrderGate gate;
    private final Clock clock;

    @Autowired
    LivePinSnapshotReader(LivePinRepository repository, ProductLookup productLookup, LiveOrderGate gate) {
        this(repository, productLookup, gate, Clock.systemUTC());
    }

    LivePinSnapshotReader(LivePinRepository repository, ProductLookup productLookup, LiveOrderGate gate, Clock clock) {
        this.repository = repository;
        this.productLookup = productLookup;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * 지금 상태를 돌려준다. 고정된 상품이 없으면(한 번도 고정된 적 없거나 해제된 뒤) seq만
     * 유지한 채 {@code UNPINNED} 모양으로 돌려준다 — 이 방송의 {@link LivePin} 행 자체가
     * 아직 없으면 seq=0, effectiveAt=지금(접속 시각, 의미 있는 과거 시각이 없다).
     */
    LivePinEventView snapshot(long broadcastId) {
        Optional<LivePin> maybePin = repository.findByBroadcastId(broadcastId);
        if (maybePin.isEmpty() || !maybePin.get().isPinned()) {
            long seq = maybePin.map(LivePin::getSeq).orElse(0L);
            var effectiveAt = maybePin.map(LivePin::getEffectiveAt).orElseGet(clock::instant);
            return LivePinEventView.unpinned(broadcastId, seq, effectiveAt);
        }
        LivePin pin = maybePin.get();
        // 재연결 스냅샷(R9.2)도 실제 남은 수량을 보여줘야 한다(R13·R14) — 중간에 몇 개 팔렸어도
        // 한도 그대로가 아니라 지금 Redis 게이트가 쥔 값을 쓴다.
        int remaining = Math.max(0, pin.getLimitedQuantity() - gate.currentCount(broadcastId, pin.getGeneration()));
        return LivePinEventView.pinned(broadcastId, pin, resolveProductName(pin.getProductId()), remaining);
    }

    String resolveProductName(long productId) {
        List<ProductLookup.Product> found = productLookup.findAll(List.of(productId));
        return found.isEmpty() ? "(알 수 없는 상품)" : found.get(0).name();
    }
}
