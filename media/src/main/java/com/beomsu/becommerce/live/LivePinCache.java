package com.beomsu.becommerce.live;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지금 고정 상태의 인메모리 캐시(R15 성능 수정) — {@link LiveOrderService#order}가 주문 시도마다
 * 거치던 MySQL 조회({@code LivePinRepository.findByBroadcastId})를 이 캐시로 대체한다.
 *
 * <p><b>왜 필요한가</b>: 거절(매진) 경로도 "지금 고정된 상품이 요청과 같은가"를 보려고 매번
 * 이 조회를 했다. 동시 주문이 Tomcat 스레드 한도(기본 100)를 넘어서면 1,000개 요청이 20개뿐인
 * Hikari DB 커넥션을 서로 기다리며 줄을 서고, 그 대기가 그대로 거절 응답 지연이 된다 — 실측(R15
 * 성능 리포트)으로 확인했다. 캐시를 쓰면 거절 경로가 DB 커넥션을 전혀 쥐지 않는다.
 *
 * <p><b>쓰기</b>: {@link LivePinService}가 고정·해제·가격 변경을 커밋한 바로 뒤에 이 캐시도
 * 갱신한다(이미 {@code broadcaster.broadcast()}를 같은 자리에서 트랜잭션 밖으로 부르던 기존
 * 관례와 같다 — "저장과 발행을 같은 트랜잭션에 묶지 않는다"는 원칙 그대로, 발행 대상이 하나
 * 늘었을 뿐이다).
 *
 * <p><b>정합성 영향</b>: 이 캐시가 잠깐 비거나(이 인스턴스가 막 뜬 직후, 또는 이 방송을 아직
 * 한 번도 못 본 경우) 낡아도(수평 확장 시 다른 인스턴스의 변경을 아직 못 받은 동안) 한정 수량
 * 초과 판매로는 이어지지 않는다 — 실제 한도는 여전히 {@link LiveOrderGate}의 Redis ZSET이
 * generation별로 지킨다(이 캐시는 "어떤 상품·세대·가격·한도로 게이트를 물을지"를 정할 뿐,
 * 그 자체가 선점 수량을 세지 않는다). 비어 있으면 {@link LiveOrderService}가 DB로 한 번
 * 폴백해서 채운다.
 */
@Component
class LivePinCache {

    private final Map<Long, LivePin> snapshots = new ConcurrentHashMap<>();

    /** 캐시된 지금 고정 상태. 없으면(아직 못 본 방송이거나 캐시가 비었으면) 비어 있다. */
    Optional<LivePin> get(long broadcastId) {
        return Optional.ofNullable(snapshots.get(broadcastId));
    }

    /** 고정·해제·가격 변경을 커밋한 뒤 호출해 캐시를 지금 상태로 맞춘다. */
    void put(LivePin pin) {
        snapshots.put(pin.getBroadcastId(), pin);
    }
}
