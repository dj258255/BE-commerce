/**
 * 라이브 방송(live) 모듈 — 스트림 키 발급·송출 인증·상태 전이(R1·R2·R3).
 *
 * <p>이 모듈은 R32에 따라 저장소 최상위 {@code media/} 영역(별도 Gradle 모듈)에 산다 —
 * {@code shorts} 모듈과 형제다. commerce 애플리케이션과 같은 jar로 배포하며, commerce가 이
 * 모듈에 의존한다({@code implementation project(':media')}) — 반대 방향 의존은 없다(ADR-080).
 *
 * <p>{@link com.beomsu.becommerce.live.LiveBroadcast}가 상태 전이
 * (SCHEDULED → LIVE → ENDED)를 갖는다(R1·R3). {@code sellerId}는 이 모듈이 소유권을 직접
 * 검증하며(IDOR 방지), 주문·결제·재고 확정 로직은 이 모듈에 전혀 두지 않는다(R32 제약) —
 * 방송 중 상품 고정·주문(다음 단계)이 와도 commerce의 공개 API(이 모듈이 정의하는 포트)만
 * 통해서 부른다.
 *
 * <p>{@link com.beomsu.becommerce.live.web.LiveBroadcastController}가 방송 생성·조회
 * API를 판매자(ROLE_SELLER)에게 내주고(R1), {@link com.beomsu.becommerce.live.web.LiveMediaHooksController}가
 * MediaMTX의 HTTP 인증 훅(R2)과 송출 시작·종료 훅(R3)을 받는다(비로그인, 스트림 키 자체가
 * 자격증명). 명세 5절은 시작·종료 훅의 전달을 Kafka로 적었지만, R23(숏폼)과 같은 이유로
 * Outbox(Spring Modulith Event Publication Registry, {@code @ApplicationModuleListener})에서
 * 시작한다 — 근거는 ADR-082.
 *
 * <p>끊김 재접속 유예(기본 30초, R3)는
 * {@link com.beomsu.becommerce.live.LiveBroadcastGraceScheduler}가 주기적으로 스캔해
 * 넘긴 방송을 끝맺는다 — {@code app.live.grace-scheduler.enabled=true}에서만 켜진다(worker·
 * local 프로파일, R23 변환 리스너와 같은 게이트 방식).
 *
 * <p>방송 중 상품 고정(R8)은 방송당 한 행({@link com.beomsu.becommerce.live.LivePin})으로
 * "동시에 고정된 상품은 항상 1개"를 강제한다. 고정·해제·가격 변경마다
 * {@link com.beomsu.becommerce.live.LivePinBroadcaster}(WebSocket, R9)로 서버 시각
 * {@code effectiveAt}과 단조 증가 {@code seq}를 실어 시청자에게 보낸다 — 근거는 ADR-084.
 * 상품 이름·실존 확인은 {@link com.beomsu.becommerce.live.ProductLookup}(media가 정의한
 * 포트, shorts의 같은 이름 포트와 같은 이유로 live가 따로 둔다)으로만 하고, 구현은 commerce
 * 쪽({@code LiveProductLookupAdapter}, order의 {@code ProductCatalogFacts}를 감싼다)에
 * 있다 — 그래서 {@code order}가 allowedDependencies에 추가됐다(shorts와 같은 이유).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "order" }
)
package com.beomsu.becommerce.live;
