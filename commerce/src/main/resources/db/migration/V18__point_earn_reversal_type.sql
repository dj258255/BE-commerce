-- 취소 시 적립(EARN)을 그 몫만큼 회수하는 EARN_REVERSAL 유형을 추가한다.
-- 구매(적립)→취소(카드환불)를 반복해 적립만 챙기는 포인트 파밍을 막는다. 이미 적립분을 소진했으면
-- 잔액이 음수(적립 채무)가 될 수 있으며, 이후 적립으로 상계된다.
alter table point_histories
    modify column type enum ('REFUND', 'RESTORE', 'USE', 'EARN', 'EARN_REVERSAL') not null;
