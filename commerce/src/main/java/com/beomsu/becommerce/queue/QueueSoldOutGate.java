package com.beomsu.becommerce.queue;

/**
 * 이 대기열이 지키는 한정 상품이 정말로 매진인지 묻는다(#385).
 *
 * <p>queue 모듈은 어떤 모듈에도 의존하지 않는다({@code allowedDependencies={}}). 그런데 매진 판정은
 * 재고(order.catalog)를 봐야 한다. 그래서 이 인터페이스는 queue 모듈이 소유하고, 구현은 order 모듈이
 * 제공한다(의존 방향은 여전히 order→queue 단방향, 역방향 의존이 생기지 않는다).
 *
 * <p>매진의 정의는 "재고가 0이고 잡힌 예약(RESERVED)도 없다"이다. 예약이 남아 있으면 결과 모름 결제가
 * 풀려 재고가 돌아올 수 있으므로 아직 매진이 아니다. 게이트 대상이 아닌 이벤트에는 항상 false를
 * 돌려주는 {@link #NEVER}를 쓴다.
 */
@FunctionalInterface
public interface QueueSoldOutGate {

    boolean isSoldOut(String eventId);

    /** 게이트 상품이 없을 때(기본) 쓰는 구현 — 절대 매진으로 판정하지 않는다. */
    QueueSoldOutGate NEVER = eventId -> false;
}
