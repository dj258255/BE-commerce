/**
 * 선착순 대기열(queue) 모듈 — 순간 트래픽 폭증으로부터 DB를 지키는 유입 제어 프리미티브.
 *
 * <p>한정판 선착순 이벤트처럼 순간에 수만 명이 몰리면, 모두가 동시에 주문·재고 차감을 때려 DB가
 * 무너진다. 그래서 결제 앞단에 <b>대기열</b>을 두어 DB 유입량을 조절한다 — 도착 순서(FIFO)로 줄을
 * 세우고, 앞에서부터 정해진 인원(admit-limit)만 "입장"시켜 결제로 흘려보낸다. 나머지는 자기 순번을
 * 폴링하며 기다린다.
 *
 * <p>줄서기 자료구조는 <b>Redis Sorted Set(ZSET)</b>이다. {@code score=도착순번}(INCR로 단조 증가),
 * {@code member=userId}로 넣으면 순번이 있는 대기열이 된다 — {@code ZRANK}가 O(log N)으로 "내 앞에
 * 몇 명"을 돌려주고, 그 rank가 admit-limit보다 작으면 입장이다. DB를 쓰지 않으므로(Redis 전용)
 * Flyway 마이그레이션도 없다.
 *
 * <p><b>크리티컬 경로(체크아웃)와는 기본 독립이다.</b> 대기열은 입장/상태/이탈만 제공하는
 * 프리미티브이고, "입장이 확인되면 결제로 진행"은 클라이언트 흐름이 조율한다. 단, <b>게이트 상품</b>
 * ({@code app.queue.gate.product-ids})으로 지정된 상품에 한해서는 order 모듈이 주문 생성 시
 * 입장권({@code hasEntryPass})을 서버가 강제한다(권고→강제, 옵트인). 대상 목록이 비어 있으면(기본)
 * 결제 경로는 대기열을 전혀 참조하지 않는다. 의존 방향은 order→queue 단방향이고, queue는 여전히
 * 어떤 모듈에도 의존하지 않으며 Spring 인프라({@code StringRedisTemplate})만 사용한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {}
)
package com.beomsu.becommerce.queue;
