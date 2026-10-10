package com.beomsu.becommerce.live;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 상품 고정 애플리케이션 서비스(R8) — live 모듈의 공개 진입점. 전부 판매자 본인 소유 +
 * {@code LIVE} 상태의 방송에서만 허용한다({@link #findOwnedLiveBroadcast}).
 *
 * <p>고정·해제·가격 변경마다 {@link LivePinBroadcaster}로 이벤트를 즉시 내보낸다(R9) — 같은
 * 트랜잭션에서 저장과 발행을 함께 하지 않는다(Outbox가 아니다). 시청자 화면 갱신은 "반드시
 * 도착해야 하는 정합성 이벤트"가 아니라 "지금 보이는 것을 맞추는 관측"에 가깝다고 보기
 * 때문이다(R9.2의 재연결 스냅샷이 유실을 스스로 복구하는 장치이기도 하다) — ADR-084 참고.
 *
 * <p>{@link Clock}을 생성자로 받는다(기본 {@link Clock#systemUTC()}) — {@code
 * LiveBroadcastService}와 같은 이 저장소의 관례.
 */
@Service
@Transactional
public class LivePinService {

    private final LivePinRepository pinRepository;
    private final LiveBroadcastRepository broadcastRepository;
    private final ProductLookup productLookup;
    private final LivePinBroadcaster broadcaster;
    private final LiveOrderGate gate;
    private final LivePinCache pinCache;
    private final Clock clock;

    @Autowired
    public LivePinService(LivePinRepository pinRepository, LiveBroadcastRepository broadcastRepository,
            ProductLookup productLookup, LivePinBroadcaster broadcaster, LiveOrderGate gate, LivePinCache pinCache) {
        this(pinRepository, broadcastRepository, productLookup, broadcaster, gate, pinCache, Clock.systemUTC());
    }

    LivePinService(LivePinRepository pinRepository, LiveBroadcastRepository broadcastRepository,
            ProductLookup productLookup, LivePinBroadcaster broadcaster, LiveOrderGate gate, LivePinCache pinCache,
            Clock clock) {
        this.pinRepository = pinRepository;
        this.broadcastRepository = broadcastRepository;
        this.productLookup = productLookup;
        this.broadcaster = broadcaster;
        this.gate = gate;
        this.pinCache = pinCache;
        this.clock = clock;
    }

    /**
     * 고정(R8.1·R8.2) — 이미 다른 상품이 고정돼 있어도 그냥 새 상품으로 덮어쓴다(같은 행,
     * 자동 해제). 방송당 행이 하나뿐이라 "동시에 고정된 상품은 항상 1개"가 저절로 지켜진다.
     */
    public LivePinEventView pin(long sellerId, long broadcastId, long productId, long price, int limitedQuantity) {
        findOwnedLiveBroadcast(sellerId, broadcastId);
        if (!productLookup.exists(productId)) {
            throw LivePinException.productNotFound(productId);
        }
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .orElseGet(() -> LivePin.forBroadcast(broadcastId, clock.instant()));
        pin.pin(productId, price, limitedQuantity, clock.instant());
        pinRepository.save(pin);
        pinCache.put(pin);   // R15: LiveOrderService가 DB 대신 보는 캐시도 바로 맞춘다

        // 새 드롭이라 세대가 막 올라갔다(generation++, R12) — 선점 0건이 보장되므로 남은 수량은
        // 늘 한도 그대로다. 그래도 공식은 하나로 통일해 둔다(priceChanged·snapshot과 같은 계산).
        int remaining = remainingQuantity(pin);
        LivePinEventView event = LivePinEventView.pinned(broadcastId, pin, resolveProductName(productId), remaining);
        broadcaster.broadcast(event);
        return event;
    }

    /** 해제(R8) — 멱등이다. 이미 고정된 상품이 없으면 새 이벤트를 내지 않고 지금 상태를 그대로 돌려준다. */
    public LivePinEventView unpin(long sellerId, long broadcastId) {
        findOwnedLiveBroadcast(sellerId, broadcastId);
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .orElseGet(() -> LivePin.forBroadcast(broadcastId, clock.instant()));

        boolean changed = pin.unpin(clock.instant());
        if (changed) {
            pinRepository.save(pin);
            pinCache.put(pin);   // R15: 해제도 즉시 캐시에 반영 — 거절 경로가 낡은 고정을 보지 않게
            LivePinEventView event = LivePinEventView.unpinned(broadcastId, pin.getSeq(), pin.getEffectiveAt());
            broadcaster.broadcast(event);
            return event;
        }
        return LivePinEventView.unpinned(broadcastId, pin.getSeq(),
                pin.getEffectiveAt() == null ? clock.instant() : pin.getEffectiveAt());
    }

    /** 가격 변경(R9.1) — 지금 고정된 상품의 특가만 바꾼다. 고정된 상품이 없으면 거절한다. */
    public LivePinEventView changePrice(long sellerId, long broadcastId, long newPrice) {
        findOwnedLiveBroadcast(sellerId, broadcastId);
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .orElseThrow(() -> LivePinException.nothingPinned(broadcastId));

        pin.changePrice(newPrice, clock.instant());
        pinRepository.save(pin);
        pinCache.put(pin);   // R15: 가격 변경도 즉시 캐시에 반영

        // 가격만 바꿔도 지금까지 팔린 수량은 그대로다 — 한도가 아니라 실제 남은 수량을 다시 구해
        // 싣는다(R13·R14, 안 그러면 판매 중간에 가격을 바꾸는 순간 "남은 수량"이 원래 한도로
        // 되돌아가 보인다).
        int remaining = remainingQuantity(pin);
        LivePinEventView event =
                LivePinEventView.priceChanged(broadcastId, pin, resolveProductName(pin.getProductId()), remaining);
        broadcaster.broadcast(event);
        return event;
    }

    /** 한도 − 지금 선점(확정 포함) 수(R13·R14) — {@code LiveOrderGate}가 쥔 실제 상태를 읽는다. */
    private int remainingQuantity(LivePin pin) {
        return Math.max(0, pin.getLimitedQuantity() - gate.currentCount(pin.getBroadcastId(), pin.getGeneration()));
    }

    private String resolveProductName(long productId) {
        List<ProductLookup.Product> found = productLookup.findAll(List.of(productId));
        return found.isEmpty() ? "(알 수 없는 상품)" : found.get(0).name();
    }

    /** 소유권(IDOR 방지) + "방송 중"(EARS 문구, R8) 둘 다 확인한다. */
    private LiveBroadcast findOwnedLiveBroadcast(long sellerId, long broadcastId) {
        LiveBroadcast broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> LiveBroadcastException.notFound(broadcastId));
        if (broadcast.getSellerId() != sellerId) {
            throw LiveBroadcastException.forbidden(broadcastId);
        }
        if (broadcast.getStatus() != LiveBroadcastStatus.LIVE) {
            throw LivePinException.broadcastNotLive(broadcastId, broadcast.getStatus());
        }
        return broadcast;
    }
}
