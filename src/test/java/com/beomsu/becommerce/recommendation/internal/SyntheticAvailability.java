package com.beomsu.becommerce.recommendation.internal;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>테스트용</b> 인메모리 가용성 — 단위 테스트가 DB 없이 제약 정책을 고정한다.
 *
 * <p><b>왜 앱에서 빠졌나(E4 후속)</b>: 앱의 제약 원천은 이제 실제 재고다
 * ({@link StockBackedAvailability} → {@code order.StockAvailabilityFacts}). 이 클래스는 예전에
 * 그 자리를 차지했지만, 메모리 집합이라 확인이 공짜여서 <b>비용 축을 잴 수 없었다</b>
 * (E4 리포트 「틀렸던 것」 ②). 앱에서는 쓰지 않고, "정책이 몇 번 확인하는가"처럼 <b>비용이 아니라
 * 횟수</b>를 보는 단위 테스트에만 남는다.
 *
 * <p>소진·해제를 인메모리로 할 수 있어 테스트가 MySQL·Redis 없이 결정적으로 돈다 —
 * {@code RecommendationServiceTest} 가 모델이 "생성 중에" 상품을 소진시키는 상황을 만들 때 쓴다.
 */
final class SyntheticAvailability implements AvailabilitySource {

    /** 지금 팔 수 없는 id. 비어 있으면 전부 팔 수 있다. */
    private final Set<Long> unavailable = ConcurrentHashMap.newKeySet();
    private final AtomicLong version = new AtomicLong();
    private final List<Long> pool;

    SyntheticAvailability() {
        this.pool = List.copyOf(ItemPool.experimentPool());
    }

    @Override
    public Set<Long> unavailableAmong(Collection<Long> itemIds) {
        Set<Long> blocked = new HashSet<>();
        for (Long itemId : itemIds) {
            // 모르는 id는 "없다"로 본다 — 모르면 통과가 아니라 거절이다.
            if (!pool.contains(itemId) || unavailable.contains(itemId)) {
                blocked.add(itemId);
            }
        }
        return blocked;
    }

    @Override
    public long version() {
        return version.get();
    }

    @Override
    public List<Long> knownItemIds() {
        return pool;
    }

    /** 하나를 팔 수 없게 만든다 — "누군가 마지막 재고를 샀다"를 흉내 낸다. */
    boolean consume(long itemId) {
        if (!pool.contains(itemId)) {
            return false;
        }
        if (unavailable.add(itemId)) {
            version.incrementAndGet();
            return true;
        }
        return false;
    }
}
