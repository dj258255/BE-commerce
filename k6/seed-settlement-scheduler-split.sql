-- 실행 단위 분리 실험용 시드 (docs/performance/settlement-scheduler-split.md)
--
-- "어제"(KST) 날짜의 CONFIRMED 정산 항목을 대량으로 심는다. 정산 스케줄러는
-- LocalDate.now(Asia/Seoul).minusDays(1) 을 집계하므로, 어제 날짜에 항목이 있어야 배치가 돈다.
--
-- 이 시드로 두 형태를 비교한다(ADR-029):
--   (before) 모놀리스 — 인스턴스 2개가 모두 app.settlement.enabled=true → 중복 집계 경합
--   (after)  실행 단위 분리 — API 2개(scheduler off) + worker 1개(scheduler on) → 단일 집계
--
-- payment_id 는 900,000,000 오프셋으로 실제 데이터와 격리한다(uk_settlement_item_payment).
-- seller_id = 1 은 플랫폼 직판(V49 에서 만든 행)이다.
--
-- 실행:
--   docker compose exec -T mysql mysql -upay -ppay pay < k6/seed-settlement-scheduler-split.sql

SET SESSION cte_max_recursion_depth = 1000000;

-- 어제(KST). 앱은 Asia/Seoul 기준 어제를 집계한다.
SET @yday := DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 9 HOUR)) - INTERVAL 1 DAY;

-- 재실행 멱등: 시드와 그 어제 정산을 지운다.
DELETE FROM settlement_items WHERE payment_id >= 900000000;
DELETE FROM settlements      WHERE settlement_date = @yday;

INSERT INTO settlement_items (payment_id, order_no, amount, confirmed_date, status, seller_id)
WITH RECURSIVE seq (n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 20000 - 1
)
SELECT
    900000000 + n,
    CONCAT('SPLIT-', LPAD(n, 10, '0')),
    10000,
    @yday,
    'CONFIRMED',
    1
FROM seq;

SELECT @yday AS target_date, COUNT(*) AS seeded
FROM settlement_items
WHERE payment_id >= 900000000;
