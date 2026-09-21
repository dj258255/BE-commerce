package com.beomsu.becommerce.recommendation.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 합성 가용성 — <b>실제 재고를 읽는 대신 실험을 위해 만든 신호</b>.
 *
 * <p><b>왜 합성인가</b>: E4가 재는 것은 "언제 확인하는가"의 교환비이고, 그 교환비가 성립하려면
 * <b>사실이 생성 중에 바뀌어야</b> 한다. 실제 재고로 그 변화를 만들려면 누군가 실제로 사야 하는데,
 * 그 속도를 실험이 정할 수 없으면 위반율이 우연에 좌우된다. 그래서 변화를 <b>실험이 주입</b>한다.
 *
 * <p><b>대신 무엇을 잃는가</b>: 이 신호는 DB도 트랜잭션도 없다. 그래서 여기서 나오는
 * <b>절대 수치를 실제 재고 확인 비용으로 읽으면 안 된다</b> — 실제 확인은 DB 왕복(수 ms)이다.
 * 읽어야 하는 것은 <b>정책 간 비교</b>이고, 그 비교에서 확인 비용은 정책마다 같은 상수다.
 * 실제 포트로 바꿔 끼우는 자리는 {@link AvailabilitySource}다.
 *
 * <p><b>정직하게 적어 둘 것</b>: 여기서 나오는 위반율은 "우리가 주입한 변화율에서의 위반율"이다.
 * 변화율이 다르면 위반율도 달라진다 — 그래서 지표에 {@code version()}을 함께 남긴다.
 */
@Component
public class SyntheticAvailability implements AvailabilitySource {

    private static final Logger log = LoggerFactory.getLogger(SyntheticAvailability.class);

    /** 지금 팔 수 없는 id. 비어 있으면 전부 팔 수 있다. */
    private final Set<Long> unavailable = ConcurrentHashMap.newKeySet();
    private final AtomicLong version = new AtomicLong();
    private final List<Long> pool;

    public SyntheticAvailability() {
        this.pool = List.copyOf(ItemPool.experimentPool());
        log.info("합성 가용성 준비 — 확인 대상 {}개(합성). 실제 재고 포트로 교체할 자리는 AvailabilitySource 다",
                pool.size());
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

    /**
     * 하나를 팔 수 없게 만든다 — "누군가 마지막 재고를 샀다"를 흉내 낸다.
     * 이미 없으면 아무 일도 하지 않는다(같은 사실을 두 번 세지 않는다).
     */
    public boolean consume(long itemId) {
        if (!pool.contains(itemId)) {
            return false;
        }
        if (unavailable.add(itemId)) {
            version.incrementAndGet();
            return true;
        }
        return false;
    }

    /** 확인 대상 중 <b>아직 팔 수 있는</b> 것을 하나 골라 소진시킨다. 전부 소진됐으면 {@code -1}. */
    public long consumeAny() {
        for (Long itemId : pool) {
            if (!unavailable.contains(itemId)) {
                consume(itemId);
                return itemId;
            }
        }
        return -1L;
    }

    /** 전부 팔 수 있게 되돌린다(런 사이 초기화). */
    public void restockAll() {
        unavailable.clear();
        version.incrementAndGet();
    }

    public int unavailableCount() {
        return unavailable.size();
    }
}
