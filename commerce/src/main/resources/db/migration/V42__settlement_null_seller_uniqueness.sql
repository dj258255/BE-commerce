-- 플랫폼 직판 정산이 같은 날짜에 두 번 생기는 것을 DB 가 막게 한다.
--
-- <b>V37 이 연 구멍이다.</b> 그때 유니크 키에 seller_id 를 더하면서
-- uk_settlement_date_currency -> uk_settlement_date_currency_seller 로 바꿨는데,
-- MySQL 은 유니크 인덱스에서 NULL 을 서로 다른 값으로 본다. seller_id 가 NULL 인 행은
-- 몇 개든 들어간다. 그리고 <b>NULL 이 곧 플랫폼 직판이라 지금 이 서비스의 기본값이다.</b>
--
-- V37 은 이걸 알고 "제약만으로는 못 막는 자리라 코드가 함께 지킨다"고 적어 뒀다. 그런데
-- 코드가 지키는 방식이 집계 전 존재 검사(SettlementRepository.existsFor)이고,
-- <b>그건 이 프로젝트가 인스턴스 둘을 띄워 실제로 뚫은 바로 그 종류의 검사다.</b>
-- 검사와 삽입 사이가 벌어지면 둘 다 통과한다. 마지막 방어선이 없는 상태였다.
--
-- 확인한 것: 같은 (2099-01-01, KRW, NULL) 을 두 번 넣었더니 두 줄 다 들어갔다.
--
-- <b>가짜 판매자 행을 만들지 않는다.</b> V37 이 NULL 을 고른 이유가 그것이라, 여기서
-- seller_id 를 0 으로 채우면 없던 판매자를 지어내는 셈이 된다. 대신 생성 컬럼으로
-- NULL 을 0 에 대응시켜 <b>그 컬럼에만</b> 유니크를 건다. seller_id 자체는 NULL 그대로다.
ALTER TABLE settlements
    ADD COLUMN seller_key BIGINT
        GENERATED ALWAYS AS (COALESCE(seller_id, 0)) STORED NOT NULL
        COMMENT '유니크 제약 전용. NULL 판매자(플랫폼 직판)를 0 으로 모은다';

ALTER TABLE settlements DROP INDEX uk_settlement_date_currency_seller;

ALTER TABLE settlements
    ADD CONSTRAINT uk_settlement_date_currency_seller UNIQUE (settlement_date, currency, seller_key);
