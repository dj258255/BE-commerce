-- 숏폼 영상-상품 다중 연결(R25). ShortVideo.productIds(@ElementCollection)의 매핑 테이블.
-- product_id는 논리적 FK다(ERD 규칙) — 물리 FK를 걸지 않는다. 복합 PK가 같은 쌍의 중복 저장을 막는다.

    create table short_video_product_links (
        short_video_id bigint not null,
        product_id bigint not null,
        primary key (short_video_id, product_id)
    ) engine=InnoDB;

    create index idx_short_video_product_links_product on short_video_product_links (product_id);
