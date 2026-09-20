-- 상품 패싯: 색상·상품 종류를 products에 붙여 색상·종류·가격 범위로 좁힐 수 있게 한다.
--
-- 왜 컬럼인가, `product_facets` 조인 테이블이 아니라: 패싯의 값은 **상품당 축마다 하나**다
-- (색상 1개, 종류 1개). 값이 하나뿐이라 조인 테이블이 값을 못 한다 — 105,542행에 대해
-- 105,542 × 축수 만큼 행이 늘고, 목록·패싯 카운트 질의가 **매번 조인**을 낀다.
-- **여러 색을 가진 상품**이 생기는 순간이 조인 테이블로 쪼갤 시점이다 — 그때는 한 상품이 한 축에
-- 여러 값을 가지므로 컬럼으로는 표현할 수 없다. 그 전까지 컬럼으로 둔다.
--
-- 왜 Elasticsearch를 지금 쓰지 않나: 105,542행은 `WHERE` + `INDEX`로 충분하다(색상·종류로 인덱스를
-- 타고 좁힌 뒤 남은 집합에서 가격을 거른다). ES는 **운영 대상**(클러스터·색인 동기화·매핑 스키마)을
-- 하나 더 늘리고, "명령 하나로 뜨는" 로컬 데모를 깬다. 측정해서 **느려지는 지점**이 나오면 그때
-- 재검토한다 — 지금 도입하면 근거 없는 의존성만 진다.
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
alter table products
    add column colour_code  varchar(40) null,
    add column colour_name  varchar(40) null,
    add column product_type varchar(80) null;

-- 색상·종류로 좁히는 목록·패싯 카운트가 스캔하는 컬럼에 인덱스를 건다.
create index idx_products_colour on products (colour_code);
create index idx_products_type   on products (product_type);
