-- 카테고리를 2단계로 만든다 — 대분류(5) 아래 중분류.
--
-- 왜 products에 컬럼을 **더하나**: `category_code`의 의미(대분류)를 바꾸면 기존 필터·문서·소비자가
-- 한꺼번에 깨진다. 대분류는 그대로 두고 중분류를 더한다. `subcategory_code`가 NULL이면 대분류만
-- 지정된 상품이다(레거시 데모 상품 1~3이 그렇다).
--
-- 왜 트리가 아니라 **조합 노드**인가: 실측에서 H&M의 대분류(성별/라인)와 중분류(상품 종류)는
-- **직교**한다 — 21개 중분류 중 2개만 한 대분류에 속한다(니트는 여성복·남성복·아동복·Divided에 다 있다).
-- 그래서 노드는 (대분류 × 중분류) 조합이고, 실제 존재하는 조합만 만든다(5×21=105 중 **72개**).
-- 중분류를 대분류의 자식으로 접으면 "여성복 > 니트"와 "Divided > 니트"가 서로 다른 노드가 된다.
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
alter table categories
    add column parent_code varchar(40) null,
    add column source_name varchar(80) null;

create index idx_categories_parent on categories (parent_code);

alter table products
    add column subcategory_code varchar(40) null;

create index idx_products_subcategory on products (subcategory_code);

-- `source_name`은 이 이름이 **어디서 왔는지**를 남긴다(H&M의 index_group_name·garment_group_name).
-- 한국어 이름을 우리가 정했으므로, 추측과 번역의 근거를 추적할 수 있어야 한다.
-- 원문을 그대로 쓴 항목(Dressed, Woven/Jersey/Knitted mix Baby)도 이 컬럼으로 구분된다.
