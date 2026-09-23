-- 상품 카탈로그: 쇼핑몰이 상품을 탐색할 수 있게 카테고리와 상품 속성을 더한다.
--
-- products 는 지금까지 가격의 서버 측 원천으로만 쓰였고(주문 시 findById 한 번), 목록·검색·
-- 카테고리 조회 표면이 없었다. 탐색용 컬럼을 붙이되 가격·재고의 권위는 그대로 둔다.
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
create table categories (
    code        varchar(40) not null,
    name        varchar(80) not null,
    description varchar(300),
    sort_order  int not null default 0,
    primary key (code)
) engine=InnoDB;

alter table products
    add column category_code varchar(40)   null,
    add column description   varchar(1000) null,
    add column image_url     varchar(500)  null,
    add column brand         varchar(120)  null,
    add column featured      tinyint(1)    not null default 0,
    add column created_at    datetime(6)   not null default current_timestamp(6);

-- 목록 필터(category_code)와 신상품 정렬(created_at)이 스캔하는 컬럼에 인덱스를 건다.
create index idx_products_category on products (category_code);
create index idx_products_created  on products (created_at);
