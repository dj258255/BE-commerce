-- 카드별 거래 이력. <b>모델이 볼 창을 캐시 밖에 한 벌 둔다.</b>
--
-- 왜 필요했나: 시퀀스 피처는 그 카드의 최근 결제들이 있어야 만들어진다. 그런데 지금 이
-- 프로젝트에 그 목록이 어디에도 없다.
--   payments        card_key 컬럼이 없다. paymentKey 만 있다
--   fraud_reviews   card_key 는 있는데 <b>걸린 건만</b> 들어간다. 정상 거래가 빠져 창이 아니다
--   Redis velocity  1분 TTL 이라 창이 아니고, 죽으면 그 집계가 통째로 사라진다
--
-- docs/17 5-1 절이 "속도 집계를 캐시에만 두고 있다" 고 적어 둔 그 구멍이 여기서 그대로 막았다.
-- 저장소를 바꾸는 이야기가 아니라 <b>같은 집계를 어디에 한 벌 더 둘 것이냐</b> 의 문제다.
-- 카카오뱅크도 거래 이벤트 프로파일을 캐시 계층에 두되 따로 영속화한다.
--
-- 카드번호는 여기 없다. card_key 는 PG 가 발급한 키이고 카드번호는 이 서버를 지나지 않는다.
CREATE TABLE card_transactions (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    card_key           VARCHAR(200) NOT NULL,
    order_no           VARCHAR(64)  NOT NULL,
    amount             BIGINT       NOT NULL,
    installment_months INT          NOT NULL DEFAULT 0,
    -- 요청 시점 신호. 사후 탐지 경로에는 안 실려 와서 지금은 NULL 이다.
    -- 이 둘이 비면 deviceChurn·ipChurn 피처가 못 돈다(docs/27 6절).
    device_id          VARCHAR(100) NULL,
    ip                 VARCHAR(45)  NULL,
    occurred_at        DATETIME(6)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    -- 아웃박스가 at-least-once 라 같은 결제 이벤트가 두 번 온다. 두 번 오면 창의 건수가
    -- 부풀고 windowCount·escalation 이 통째로 틀어진다. 주문 하나에 한 줄만 둔다.
    CONSTRAINT uk_card_txn_order UNIQUE (order_no),
    -- 창 조회가 쓰는 인덱스. card_key 로 좁히고 occurred_at 으로 정렬한다.
    KEY idx_card_txn_window (card_key, occurred_at)
);
