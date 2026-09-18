-- ADR-024 C 안: 전환 시점에 미확정인 정산 항목만 새 저장소로 옮긴다.
--
-- 왜 이것만 옮기는가: 에스크로 보류가 기본 7일이라 승인과 구매확정 사이가 최대 7일 벌어진다.
-- 새 서비스를 빈 저장소로 띄우면 그 7일치의 구매확정이 도착했을 때 대응할 항목이 없고,
-- 재전달해도 영원히 생기지 않는다. 옛 코드에서는 경고 한 줄로 지나가 조용한 지급 누락이 됐다.
-- 지금은 settlement.confirm.missing_item 으로 세어지고, 전환 기간에는
-- app.settlement.missing-item-is-incident=true 로 켜서 예외로 올린다.
--
-- 이미 SETTLED 된 과거는 옮기지 않는다. 그건 "과거 조회를 한 곳에서 할 것인가"라는 다른
-- 질문이고, 그 요구가 실제로 생긴 뒤에 고른다(ADR-024 의 A·B 안).
--
-- 사용: docker exec -i pay-mysql-1 mysql --default-character-set=utf8mb4 -upay -ppay pay \
--         < tools/export-pending-settlement-items.sql > pending-items.tsv

-- 1. 옮길 양을 먼저 본다. 대략 7일치가 나와야 하고, 그보다 긴 꼬리가 있으면
--    C 안이 덮지 못하는 구간이 있다는 뜻이다(자동 릴리스가 꺼져 있는 등).
SELECT
    COUNT(*)                                    AS 옮길_항목수,
    SUM(amount)                                 AS 금액합,
    MIN(confirmed_date)                         AS 가장_오래된_적재일,
    DATEDIFF(CURDATE(), MIN(confirmed_date))    AS 최장_경과일
FROM settlement_items
WHERE status = 'PENDING_CONFIRMATION';

-- 2. 옮길 행. 새 서비스의 settlement_items 로 그대로 들어간다.
--    id 는 옮기지 않는다 — 새 저장소가 자기 시퀀스를 쓰고, 멱등은 payment_id 유니크가 잡는다.
SELECT
    payment_id,
    order_no,
    amount,
    confirmed_date,
    status,
    seller_id,
    last_cancel_seq
FROM settlement_items
WHERE status = 'PENDING_CONFIRMATION'
ORDER BY id;

-- 3. 전환 뒤 대조. 새 저장소에서 같은 질의를 돌려 1번의 건수·금액과 맞는지 본다.
--    눈으로 맞추지 말고, 안 맞으면 전환을 멈춘다. 이 대조가 되돌릴 수 있는 마지막 지점이다.
