-- 포인트 적립(EARN)을 도입하면서 point_histories.type enum에 EARN을 추가한다.
-- 기존 값(USE/RESTORE/REFUND)은 그대로 두고 EARN만 확장 — 데이터 마이그레이션 없음.
alter table point_histories
    modify column type enum ('REFUND', 'RESTORE', 'USE', 'EARN') not null;
