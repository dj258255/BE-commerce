-- FDS 심사 큐 멱등 가드.
--
-- 아웃박스는 at-least-once 다. 재발행(재기동 재발행·프로듀서 재시도)으로 같은
-- PaymentConfirmedEvent 가 두 번 배달될 수 있고, 그때 FraudPostHocListener 가 REVIEW/BLOCK
-- 판정을 다시 내리면 심사 큐에 <b>같은 주문이 두 줄</b>로 쌓인다.
--
-- 거래 이력(card_transactions)은 order_no 유니크로 막고 있었는데(fraud/FraudPostHocListener.record),
-- 정작 <b>사람이 보는 심사 큐는 막는 장치가 없었다.</b> 한 주문에 심사는 하나다.
-- 검사만으로는 동시 진입을 못 막으므로(상황 2.2) 유니크 제약을 함께 건다.
--
-- 기존 중복이 있으면 이 마이그레이션이 실패한다 — 조용히 넘어가는 것보다 멈추는 편이 낫다.

ALTER TABLE fraud_reviews
    ADD CONSTRAINT uk_fraud_review_order UNIQUE (order_no);
