-- 정산 항목 생명주기를 에스크로에 정렬한다.
-- 기존 PENDING/SETTLED 2상태에서 PENDING_CONFIRMATION/CONFIRMED/SETTLED/CANCELED 4상태로 확장한다.
--
-- 전이 정책:
--   구 PENDING(승인·미집계)은 구 로직상 곧 집계 대상이었으므로 신규에서 CONFIRMED(정산 가능)로 옮긴다
--   — 구매확정 개념이 없던 과거 데이터를 정산 가능으로 보존해 하위 호환을 지킨다.
--   구 SETTLED는 그대로 SETTLED.
--
-- 두 번의 ALTER는 의도적이다: 1) 신구 값을 모두 허용하도록 enum을 넓혀 UPDATE로 값을 이전한 뒤,
-- 2) 최종 enum을 Java enum 선언 순서(PENDING_CONFIRMATION,CONFIRMED,SETTLED,CANCELED)와 일치시켜
-- 확정한다 → ddl-auto=validate 통과.

alter table settlement_items
    modify status enum ('PENDING','SETTLED','PENDING_CONFIRMATION','CONFIRMED','CANCELED') not null;

update settlement_items set status = 'CONFIRMED' where status = 'PENDING';

alter table settlement_items
    modify status enum ('PENDING_CONFIRMATION','CONFIRMED','SETTLED','CANCELED') not null;
