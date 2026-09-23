-- 인기 통계(M1) → 서빙용 표. 홈의 "인기" 행이 **진짜 인기**가 되도록 (#198②).
--
-- 왜 표인가: 인기 통계는 **오프라인 배치**가 만든다(3,178만 거래를 요청마다 셀 수 없다). 서빙은 그
-- 결과만 읽는다. 적재는 `personalization/pipeline/export_popular.py --emit-sql --load` — 스키마가
-- 아니라 데이터라 마이그레이션에 행을 넣지 않는다(`products` 승격과 같은 규칙).
--
-- 왜 `computed_at` 이 필요한가: 이 데이터는 2020-09-22 에 끝난다. "최근 7일"은 **벽시계가 아니라
-- 데이터 기준일**의 7일이다 — 화면이 "요즘 인기"라고 말할 때 그 "요즘"이 언제인지 값이 스스로
-- 밝혀야 한다(아니면 2020년의 인기를 2026년의 인기처럼 보여주게 된다).
--
-- 창(window)이 둘인 이유: M1 의 baseline 이 `popular_recent7d` 와 `popular_all` 을 나눠 쟀고
-- (최신성이 지표에 기여하는지 확인하려고), 서빙도 같은 선택지를 갖는다.

create table product_popularity (
    id             bigint       not null auto_increment,
    product_id     bigint       not null,
    -- 'recent_7d' | 'all_time'
    -- 컬럼명이 `window_kind`·`rank_no` 인 이유: MySQL 8 에서 `window`·`rank` 는 **예약어**다
    -- (윈도우 함수). 예약어를 그대로 쓰면 DDL·조회가 백틱 없이는 깨진다.
    window_kind    varchar(20)  not null,
    rank_no        int          not null,
    purchase_count bigint       not null,
    -- 데이터 기준일 — 이 값이 "최근"의 기준이다.
    computed_at    date         not null,
    primary key (id),
    -- 서빙 조회는 "창 하나에서 순위 순"이다 — 그 모양으로 인덱스를 준다.
    key idx_product_popularity_window_rank (window_kind, rank_no),
    key idx_product_popularity_product (product_id)
) engine=InnoDB;
