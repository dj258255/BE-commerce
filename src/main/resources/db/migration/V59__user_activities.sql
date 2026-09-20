-- 개인화 활동 로그(합성). E1의 이벤트 원천이자 E2의 "동일 로그"다.
--
-- 실제 사용자 행동이 아니라 부하 생성기가 흘린 것이다 — source='SYNTHETIC'으로 구분한다.
-- 개인화 문서 규칙: 합성 데이터를 쓴 실험은 그 사실을 결과에 적는다(personalization/docs/00-data.md §5).
--
-- uk (user_id, seq): 생성기가 부여한 사용자별 순번이다. 두 가지를 한 번에 맡는다.
--   ① 순서 역전 방어의 기준 — 컨텍스트 적용은 seq가 클 때만 한다
--   ② 멱등 — 컨슈머가 at-least-once로 같은 이벤트를 다시 받아도 DB는 1건이다
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
-- item_id는 products를 논리적으로 가리키지만 **검증하지 않는다**. 합성 이벤트가 실제 상품을
-- 가리키지 않아도 컨텍스트 실험에는 무관하고, 여기서 상품을 읽으면 개인화 경계 규칙
-- (personalization/docs/01-architecture.md §5 "커머스 도메인 테이블 직접 조회 금지")에 걸린다.
create table user_activities (
    id            bigint      not null auto_increment,
    user_id       bigint      not null,
    item_id       bigint      not null,
    activity_type varchar(20) not null,                      -- CLICK / VIEW
    seq           bigint      not null,                      -- 사용자별 단조 증가(생성기가 부여)
    source        varchar(20) not null,                      -- SYNTHETIC (실데이터와 구분)
    occurred_at   datetime(6) not null,
    created_at    datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_user_activity_seq (user_id, seq),
    key idx_user_activity_user_time (user_id, occurred_at)
) engine=InnoDB;
