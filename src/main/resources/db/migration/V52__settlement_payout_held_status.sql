-- settlements.status enum 에 PAYOUT_HELD 를 추가한다 (잠복 버그 수정).
--
-- <b>무슨 일이 있었나.</b> V11 이 status 를 MySQL <b>ENUM('CREATED','PAID_OUT')</b> 로 만들었다.
-- 그런데 V40 이 지급 보류를 넣으면서 주석에 이렇게 적었다:
--
--   "status 는 VARCHAR 라 값 추가에 제약 변경이 필요 없다. PAYOUT_HELD 가 늘어난다."
--
-- <b>틀렸다.</b> 컬럼은 VARCHAR 가 아니라 ENUM 이다. 그래서 Java enum(SettlementStatus)에는
-- PAYOUT_HELD 가 있는데 DB enum 에는 없고, {@code Settlement.holdPayout} 이 PAYOUT_HELD 를 쓰는
-- 순간 MySQL 이 값을 거부한다(Data truncated for column 'status'). <b>제재 스크리닝에 걸려 지급이
-- 보류되는 경로가 통째로 실패했다</b> — H2 기반 단위 테스트는 enum 을 강제하지 않아 통과한다.
-- 원장 AccountType enum(V50)과 정확히 같은 종류의 버그다.
--
-- 순서를 Java enum 선언(CREATED, PAYOUT_HELD, PAID_OUT)과 맞춘다.

ALTER TABLE settlements
    MODIFY COLUMN status ENUM('CREATED', 'PAYOUT_HELD', 'PAID_OUT') NOT NULL;
