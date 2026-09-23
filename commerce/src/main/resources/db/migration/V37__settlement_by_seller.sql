-- 정산을 판매자별로 가른다.
--
-- 지금까지 정산은 일자·통화별 집계였다. 실제 정산은 <누군가에게> 하는데 받는 쪽이 없었다.
-- 판매자 도메인(V36)을 만들었으니 정산이 그쪽을 향하게 한다.
--
-- <b>seller_id 를 NULL 허용으로 둔다.</b> 기존 주문은 플랫폼이 직접 판 것이고, 그건 사실이다.
-- 여기에 가짜 판매자 행을 만들어 채우면 없던 판매자를 지어내는 것이 된다.
-- NULL 은 "플랫폼 직판"을 뜻하고, 집계는 NULL 도 하나의 묶음으로 센다.
ALTER TABLE settlement_items
    ADD COLUMN seller_id BIGINT NULL COMMENT '정산을 받을 판매자. NULL 이면 플랫폼 직판',
    ADD KEY idx_settlement_item_seller (seller_id, status, confirmed_date);

-- 정산의 유니크 키에 판매자를 더한다.
--
-- <b>여기가 위험한 자리다.</b> 이 키는 이미 한 번 조용히 틀려서(승인일로 스탬프) 가맹점 지급이
-- 통째로 빠진 적이 있다. 판매자를 더하면서 <b>멱등 검사도 판매자별</b>이 되어야 한다.
-- 날짜만 보고 건너뛰면, 나중에 등록된 판매자의 정산이 영영 안 나간다.
--
-- MySQL 의 유니크 인덱스는 NULL 을 서로 다른 값으로 취급한다. 그대로 두면 플랫폼 직판
-- 정산이 같은 날짜에 여러 개 생길 수 있으므로, 집계 쪽에서 NULL 을 하나의 묶음으로 다루고
-- 존재 검사도 그렇게 한다. 제약만으로는 못 막는 자리라 코드가 함께 지킨다.
ALTER TABLE settlements
    ADD COLUMN seller_id BIGINT NULL COMMENT '정산을 받을 판매자. NULL 이면 플랫폼 직판';

ALTER TABLE settlements DROP INDEX uk_settlement_date_currency;

ALTER TABLE settlements
    ADD CONSTRAINT uk_settlement_date_currency_seller UNIQUE (settlement_date, currency, seller_id);
