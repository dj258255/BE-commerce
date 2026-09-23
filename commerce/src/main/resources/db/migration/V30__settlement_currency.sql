-- 정산을 통화별로 가른다.
--
-- 왜: uk_settlement_date 가 (settlement_date) 하나라, 같은 날 KRW 정산과 USD 정산이 둘 다 나올 수
-- 없다. 다통화를 붙이는 순간 이 제약이 <정상 동작>을 유니크 위반으로 막는다. 그런데 이 제약은
-- "같은 날짜를 두 번 집계해 가맹점 지급이 두 배가 되는 것"을 막던 자리라 급하다고 풀면 안 된다.
-- 통화를 키에 넣어 둘 다 지킨다.
--
-- 지금 이 시스템은 KRW 만 만든다. 기존 행은 전부 KRW 다.

ALTER TABLE settlements
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'KRW' COMMENT 'ISO 4217 통화 코드. 정산은 통화별로 따로 만든다';

ALTER TABLE settlements DROP INDEX uk_settlement_date;

ALTER TABLE settlements
    ADD CONSTRAINT uk_settlement_date_currency UNIQUE (settlement_date, currency);

ALTER TABLE settlements
    ALTER COLUMN currency DROP DEFAULT;
