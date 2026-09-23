-- 월렛을 체크아웃 결제수단으로 배선하면서, 결제 차감(USE)·환불(REFUND)을 주문 단위로 멱등화한다.
-- 사가 재진입(크래시 복구가 settle을 재실행)·"따닥" 중복요청에도 같은 주문의 차감/환불이 한 번만 반영되도록,
-- 거래 이력에 order_no를 남기고 (order_no, type)에 유니크 인덱스를 건다.
--
-- 충전(CHARGE)은 주문과 무관하므로 order_no가 NULL이다. MySQL 유니크 인덱스는 NULL을 중복으로 보지 않으므로
-- 충전 이력은 서로 충돌하지 않고, USE/REFUND만 주문당 1건으로 강제된다.
alter table wallet_transactions
    add column order_no varchar(64) null;

create unique index uk_wallet_tx_order_type
    on wallet_transactions (order_no, type);
