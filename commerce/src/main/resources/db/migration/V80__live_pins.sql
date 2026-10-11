-- 방송 중 상품 고정(R8) — 방송당 한 행으로 "동시에 고정된 상품은 항상 1개"를 강제한다.
--
-- productId/price/limitedQuantity는 전부 null 허용이다: 아직 아무것도 고정하지 않은(또는
-- 해제된) 방송은 이 세 값이 모두 null인 행으로 표현된다(LivePin.forBroadcast). seq는 그
-- 상태에서도 과거 이벤트 수를 누적해 가지고 있어야 해서 not null·기본 0이다.
--
-- productId는 카탈로그 상품을 논리적으로만 가리킨다 — FK를 걸지 않는다(이 저장소의 "논리적
-- FK + 인덱스" 관례, user_activities·home_impressions와 같다). 실존 확인은
-- media(live)의 ProductLookup 포트가 쓰기 시점에 한다.

create table live_pins (
    id               bigint      not null auto_increment,
    broadcast_id     bigint      not null,
    product_id       bigint      null,
    price            bigint      null,
    limited_quantity int         null,
    seq              bigint      not null default 0,
    effective_at     datetime(6) null,
    updated_at       datetime(6) not null,
    primary key (id),
    constraint uk_live_pins_broadcast unique (broadcast_id)
) engine=InnoDB;
