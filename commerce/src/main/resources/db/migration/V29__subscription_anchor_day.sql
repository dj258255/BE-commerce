-- 구독 청구 앵커(anchor day) 도입.
--
-- 왜: 다음 청구일을 <직전 청구일 + 1개월>로 계산하면, 말일이 없는 달에서 한 번 당겨진 날이
-- 영영 돌아오지 않는다. 1/31 → 2/28 → 3/28 → 4/28 로 손실이 누적된다(3월엔 31일이 있는데도).
-- 원래 청구하기로 한 일자를 따로 들고 매달 그 달 길이에 맞춰 클램프하면 1/31 → 2/28 → 3/31 이 된다.
-- Stripe 의 billing cycle anchor 와 같은 방식이다.

ALTER TABLE subscriptions
    ADD COLUMN anchor_day INT NOT NULL DEFAULT 1 COMMENT '원래 청구하기로 한 일자(1~31). 그 달에 없으면 말일로 클램프한다';

-- 기존 행 백필. 이미 클램프된 행은 원래 앵커를 복원할 수 없다.
-- 다만 <한 번만> 당겨진 행은 직전 주기 시작일에 원래 일자가 남아 있으므로 둘 중 큰 쪽이 앵커다.
--   1/31 → 2/28 인 행: GREATEST(31, 28) = 31  (복원됨)
--   2/28 → 3/28 인 행: GREATEST(28, 28) = 28  (이미 소실. 복원 불가)
UPDATE subscriptions
SET anchor_day = GREATEST(DAYOFMONTH(current_period_start), DAYOFMONTH(next_billing_date));

ALTER TABLE subscriptions
    ALTER COLUMN anchor_day DROP DEFAULT;
