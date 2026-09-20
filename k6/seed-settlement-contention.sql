-- 정산 배치 ↔ 결제 API 경합 실험용 시드 (docs: ROADMAP-MSA.local.md 실험 1)
--
-- 배치(settle)는 해당 날짜의 CONFIRMED 항목이 없으면 즉시 반환하므로, 경합을 만들려면
-- 배치가 씹을 항목을 대량으로 심어야 한다. 날짜당 ITEMS_PER_DATE건 × 10개 날짜를 심는다.
-- 배치 1회 = "SELECT 2만 건 + 전건 UPDATE(markSettled)"가 한 트랜잭션에 커밋되어
-- 커넥션을 길게 점유한다 — 이것이 측정하려는 배치성 부하다.
--
-- 설계 근거:
--   * 날짜를 2001-01-01~10로 잡는다 — settlements.settlement_date가 유니크라
--     실제 스케줄러가 집계하는 "어제" 날짜와 충돌하지 않게 과거로 격리한다.
--   * payment_id는 900,000,000 오프셋 — 실데이터와 유니크 제약(uk_settlement_item_payment)
--     충돌 방지 + 재실행 시 시드만 선별 삭제.
--
-- 실행:
--   docker compose exec -T mysql mysql -ubecommerce -pbecommerce becommerce < k6/seed-settlement-contention.sql
-- (컨테이너/계정명이 다르면 compose.yaml 기준으로 맞춘다)

SET SESSION cte_max_recursion_depth = 1000000;

-- 재실행 멱등: 이전 시드와 그 정산 결과를 지운다
DELETE FROM settlement_items WHERE payment_id >= 900000000;
DELETE FROM settlements WHERE settlement_date BETWEEN '2001-01-01' AND '2001-01-10';

-- 날짜 10개 × 20,000건 = 200,000건
-- (부하를 키우려면 20000을 올린다. 배치 1회 트랜잭션 크기 = 이 값)
INSERT INTO settlement_items (payment_id, order_no, amount, confirmed_date, status)
WITH RECURSIVE seq (n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10 * 20000 - 1
)
SELECT
    900000000 + n,                                          -- payment_id (실데이터와 격리)
    CONCAT('SEED-', LPAD(n, 10, '0')),                      -- order_no
    10000,                                                  -- amount (10,000원 고정)
    DATE_ADD('2001-01-01', INTERVAL n DIV 20000 DAY),       -- 날짜당 20,000건씩 배분
    'CONFIRMED'                                             -- 배치 집계 대상 상태
FROM seq;

SELECT confirmed_date, COUNT(*) AS items
FROM settlement_items
WHERE payment_id >= 900000000
GROUP BY confirmed_date
ORDER BY confirmed_date;
