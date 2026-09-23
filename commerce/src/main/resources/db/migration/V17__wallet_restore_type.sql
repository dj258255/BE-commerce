-- 월렛 예약 해제(RESTORE)를 취소 환불(REFUND)과 구분한다(point의 RESTORE/REFUND 계약과 동일).
--   - RESTORE: 사가 보상(승인 실패·재고 부족)의 예약 해제 — 주문 단위 멱등.
--   - REFUND : 완료 결제의 취소 환불 — 부분취소가 여러 번 올 수 있어 비멱등.
alter table wallet_transactions
    modify column type enum ('CHARGE', 'USE', 'RESTORE', 'REFUND') not null;

-- (order_no, type) 유니크 인덱스를 제거한다. 거절→재시도로 USE가 2건 쌓일 수 있고, 부분취소로 REFUND가
-- 여러 건 쌓일 수 있어야 하므로 주문당 1건 제약은 더 이상 맞지 않다. 이중반영은 상위 Order.startPayment
-- (@Version) 직렬화 + 서비스의 활성예약(USE−RESTORE−REFUND) 멱등 판정으로 막는다(point와 동일 방식).
drop index uk_wallet_tx_order_type on wallet_transactions;
