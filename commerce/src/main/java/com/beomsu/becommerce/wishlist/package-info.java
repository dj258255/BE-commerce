/**
 * 위시리스트(wishlist) 모듈 — 찜(사용자 × 상품).
 *
 * <p><b>왜 별도 모듈인가</b>: 찜은 주문·결제 흐름의 일부가 아니라 <b>사용자 표면</b>이고,
 * 동시에 <b>개인화 신호</b>다(구매보다 자주 발생해 콜드스타트에 유리하다). order 모듈에 얹으면
 * 경계가 흐려지고, 나중에 개인화 영역이 찜을 소비할 때 order를 통째로 거쳐야 한다.
 *
 * <p><b>order에 의존하는 이유</b>: 찜을 걸기 전에 상품 실존을 확인하고, 목록을 그릴 때 카드 값을
 * 붙여야 한다. 그런데 상품은 {@code order.catalog}(order의 내부 패키지)에 있어 밖에서 import할 수
 * 없다. order가 루트에 열어 둔 {@link com.beomsu.becommerce.order.ProductCatalogFacts}가 그 유일한
 * 통로다(ADR-018). order는 wishlist를 모르므로 순환은 없다.
 *
 * <p>shared는 {@code DomainException}(에러 코드 체계)만 쓴다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "order" }
)
package com.beomsu.becommerce.wishlist;
