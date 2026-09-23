-- 합성 리뷰(#168) — **실데이터가 아니다.** 그 사실이 세 곳(DB·API·화면)에서 드러나야 한다.
--
-- 왜 합성인가: H&M 코퍼스에 **리뷰가 없다**(구매 이력·상품 메타만 있다). Amazon 리뷰 250만 건은
-- **다른 카탈로그**라 조인 키가 없다(실측). 상세 화면이 비어 보이는 것과 거짓을 만드는 것 사이에서
-- "합성임을 밝히고 쓰는" 쪽을 골랐다(ADR-046).
--
-- 왜 `source` 가 NOT NULL 인가: 이 표에 **실데이터가 섞여 들어오는 순간** 화면이 거짓이 된다.
-- 지금 허용되는 값은 `SYNTHETIC` 하나이고, 실데이터가 생기면 값을 늘리는 것이 아니라
-- **집계·정렬 경로를 다시 설계**해야 한다(그때까지 리뷰 기반 정렬·필터는 만들지 않는다).
--
-- 왜 평점 평균 컬럼이 `products` 에 없는가: 편의를 위해 비정규화하는 순간 그 값이 **상품의 품질
-- 신호처럼** 쓰인다. 평균을 두지 않으면 그 통로 자체가 없다 — 없는 것이 이 설계의 일부다.

create table product_reviews (
    id           bigint       not null auto_increment,
    product_id   bigint       not null,
    -- 지금은 'SYNTHETIC' 뿐이다. 이 값이 화면 문구와 API 필드의 근거다.
    source       varchar(20)  not null,
    -- 리뷰 본문과 별점. 별점은 **이 블록 안에서만** 쓴다(집계하지 않는다).
    rating       int          not null,
    body         varchar(500) not null,
    author_label varchar(60)  not null,
    created_at   datetime(6)  not null,
    primary key (id),
    key idx_product_reviews_product (product_id, id)
) engine=InnoDB;
