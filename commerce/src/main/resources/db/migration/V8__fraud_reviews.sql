-- FDS 심사 큐 — 비동기 사후 탐지가 REVIEW/BLOCK으로 판정한 결제를 사람 검토 대상으로 적재.
-- PENDING→APPROVED/REJECTED. 거부 시 카드(card_key)를 블랙리스트에 등록한다.

    create table fraud_reviews (
        amount bigint not null,
        payment_id bigint not null,
        score integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        reviewed_at datetime(6),
        card_key varchar(200) not null,
        order_no varchar(200) not null,
        reasons varchar(500),
        reviewed_by varchar(100),
        decision enum ('ALLOW','CHALLENGE','REVIEW','BLOCK') not null,
        status enum ('PENDING','APPROVED','REJECTED') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_fraud_review_status on fraud_reviews (status);
