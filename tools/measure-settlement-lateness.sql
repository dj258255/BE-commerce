-- ADR-023 과 ADR-024 를 고르기 전에 확인할 것. 코드 변경 없이 기존 데이터로만 잰다.
--
-- settle() 이 confirmed_date <= date 인 항목을 전부 쓸어 담기 때문에, 정산 한 건에는
-- 그날 확정된 것과 며칠 전에 확정됐다가 밀린 것이 섞인다. 얼마나 섞이는지 아무도 세지 않았다.
--
-- 사용: docker exec -i pay-mysql-1 mysql --default-character-set=utf8mb4 -ubecommerce -pbecommerce becommerce \
--         < tools/measure-settlement-lateness.sql
--
-- --default-character-set=utf8mb4 를 빼면 한글 별칭이 깨져 1064 문법 오류가 난다.

-- 1. 지각 도착 비율과 지연 일수 분포
--    settlement_date 는 쓸어 담은 날, confirmed_date 는 구매확정일이다. 둘의 차이가 지연이다.
--    0 에 몰려 있으면 ADR-023 의 B 안(귀속일 분리)은 복잡도만 늘린다.
SELECT
    DATEDIFF(s.settlement_date, i.confirmed_date)       AS 지연일수,
    COUNT(*)                                            AS 항목수,
    SUM(i.amount)                                       AS 금액합,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)  AS 비율_퍼센트
FROM settlement_items i
JOIN settlements s ON s.id = i.settlement_id
WHERE i.status = 'SETTLED'
GROUP BY 지연일수
ORDER BY 지연일수;

-- 2. 아직 쓸리지 않고 남아 있는 확정 항목
--    chunk() 상한에 걸려 밀리는 것이 있는지 본다. 오래된 날짜가 쌓여 있으면 그건 정책 문제가
--    아니라 배치 문제이고, ADR-023 을 고르기 전에 먼저 고쳐야 한다.
SELECT
    i.confirmed_date,
    COUNT(*)                                AS 미정산_항목수,
    SUM(i.amount)                           AS 금액합,
    DATEDIFF(CURDATE(), i.confirmed_date)   AS 며칠째
FROM settlement_items i
WHERE i.status = 'CONFIRMED'
GROUP BY i.confirmed_date
ORDER BY i.confirmed_date;

-- 3. 월 경계를 넘어간 항목
--    지난달 확정분이 이번달 정산에 들어가면 월 단위 리포트가 그 달 거래와 어긋난다.
--    0 건이면 ADR-023 의 문제 2 는 아직 실재하지 않는다.
SELECT
    DATE_FORMAT(i.confirmed_date, '%Y-%m')  AS 확정월,
    DATE_FORMAT(s.settlement_date, '%Y-%m') AS 집계월,
    COUNT(*)                                AS 항목수,
    SUM(i.amount)                           AS 금액합
FROM settlement_items i
JOIN settlements s ON s.id = i.settlement_id
WHERE i.status = 'SETTLED'
  AND DATE_FORMAT(i.confirmed_date, '%Y-%m') <> DATE_FORMAT(s.settlement_date, '%Y-%m')
GROUP BY 확정월, 집계월
ORDER BY 확정월, 집계월;

-- 4. 이미 나간 정산을 되돌리는 조정이 얼마나 되는가
--    취소 쪽 이월(SettlementAdjustment)의 실제 부피다. 이 값이 크면 판매자가 보는 오늘 금액에
--    음수가 자주 섞인다는 뜻이고, ADR-023 의 설명 가능성 문제가 그만큼 커진다.
SELECT
    status                                  AS 조정상태,
    COUNT(*)                                AS 건수,
    SUM(adjustment_amount)                  AS 회수액합
FROM settlement_adjustments
GROUP BY status;

-- 5. ADR-024 의 이관 범위 확인
--    전환 시점에 PENDING_CONFIRMATION 으로 남아 있을 항목이 얼마나 되는지다.
--    에스크로 홀드가 7일이므로 대략 7일치가 나와야 한다. 이보다 긴 꼬리가 있으면
--    ADR-024 의 C 안(미결 항목만 이관)이 덮지 못하는 구간이 있다는 뜻이다.
SELECT
    COUNT(*)                                    AS 미확정_항목수,
    SUM(amount)                                 AS 금액합,
    MIN(confirmed_date)                         AS 가장_오래된_적재일,
    DATEDIFF(CURDATE(), MIN(confirmed_date))    AS 최장_경과일
FROM settlement_items
WHERE status = 'PENDING_CONFIRMATION';
