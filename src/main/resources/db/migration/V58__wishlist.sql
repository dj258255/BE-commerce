-- 위시리스트(찜): 사용자 × 상품.
--
-- 왜 서버에 저장하나: 찜은 **개인화 신호**다(구매보다 자주 발생해 콜드스타트에 유리하다).
-- 장바구니는 localStorage에 두고 결제 진입 시점에만 로그인을 요구해도 손실이 없다 —
-- "지금 사려는 것"은 그 순간의 브라우저 상태면 충분하다. 그러나 찜을 브라우저에만 두면
-- personalization 영역이 소비할 수 없다. 그래서 찜만 서버에 남기고 로그인을 필수로 둔다.
-- (장바구니와 정책이 갈리는 지점이고, 그 대가는 ADR-033에 적었다.)
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
-- 유니크 (user_id, product_id) 하나가 **멱등 보장과 조회 인덱스를 겸한다** — 선두 컬럼이
-- user_id라 "내 찜 목록" 조회가 이 인덱스를 탄다. 별도 idx_wishlist_user 를 만들지 않는다.
-- 같은 상품을 두 번 찜하면 두 번째 INSERT가 유니크로 막혀 행이 1건으로 유지된다.
create table wishlist_items (
    id         bigint      not null auto_increment,
    user_id    bigint      not null,
    product_id bigint      not null,
    created_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_wishlist_user_product (user_id, product_id)
) engine=InnoDB;
