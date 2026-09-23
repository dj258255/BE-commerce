-- 개인화 유저 매핑 — H&M customer_id(1.37M)와 커머스 user_id를 잇는다(personalization/docs/04-storage.md §4).
--
-- 왜 별도 매핑 표인가: **137만 고객을 커머스 회원으로 만들지 않는다.** 회원은 가입·인증의 주체이고
-- H&M 고객은 거래 로그의 식별자라 개념이 다르다(데모 규모에도 과하다). 회원 도메인은 그대로 두고
-- 필요한 만큼만 여기서 잇는다.
-- **매핑이 없으면** 인기 폴백으로 응답한다 — 콜드스타트와 같은 경로다. 조회 실패를 위한 별도 분기를
-- 만들지 않으므로, 이 표는 "행이 있으면 매핑, 없으면 폴백"이라는 한 가지 규칙만 갖는다.
--
-- 유니크는 user_id에 건다: 서빙은 `userId → hm_customer_id` 방향으로 먼저 읽는다(위 폴백 경로).
-- 한 user_id에 행이 둘이면 그 조회가 모호해지므로 user_id를 유니크로 잡아 **매핑을 함수로** 만든다 —
-- 이것이 "한 사람이 두 번 들어가지 않게"의 자리다. 적재가 재실행돼도 두 번째 INSERT가 유니크로
-- 막혀 멱등이다. 반대 방향(hm_customer_id → user_id)은 조회만 필요하므로 유니크가 아니라 인덱스로
-- 받는다(유니크를 양쪽에 걸면 이유 없이 쓰기 제약만 늘어난다).
--
-- hm_customer_id는 **문자열로 유지한다**: 원본이 64자 hex 문자열이라 숫자로 바꾸면 앞자리가 날아간다
-- (personalization/pipeline/README.md, docs/00-data.md 함정 목록). article_id를 문자열로 두는 것과
-- 같은 결이다. 양쪽 다 NOT NULL인 이유: 행의 존재 자체가 "이 회원 = 이 H&M 고객"이라는 뜻이라
-- 한쪽이 비면 매핑이 아니다(매핑 없음은 NULL이 아니라 행 없음으로 표현한다).
--
-- FK 제약은 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스)에 맞춰 인덱스만 만든다.
-- user_id는 members를 논리적으로 가리키지만 검증하지 않는다.
create table personalization_user_map (
    id             bigint      not null auto_increment,
    user_id        bigint      not null,
    hm_customer_id varchar(64) not null,
    created_at     datetime(6) not null default current_timestamp(6),
    primary key (id),
    -- 유니크가 user_id 조회 인덱스도 겸한다 — 별도 idx를 만들지 않는다.
    unique key uk_personalization_user_map_user (user_id),
    -- 역방향 조회(hm_customer_id → user_id)용. 유니크가 아니다(위 주석).
    key idx_personalization_user_map_hm_customer (hm_customer_id)
) engine=InnoDB;
