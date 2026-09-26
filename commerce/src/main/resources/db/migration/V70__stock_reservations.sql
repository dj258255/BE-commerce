-- #374 재고 예약. 결제 시작(AT_PAYMENT)이나 주문 생성(AT_ORDER) 때 재고를 조건부 UPDATE 로 빼고
-- 그 사실을 여기 남긴다. 승인이면 CLAIMED(재고는 이미 빠져 있다), 거절 · 만료면 RELEASED(재고를 되돌린다).
-- 기본 전략(NONE)에서는 이 테이블에 아무것도 쓰지 않는다.
CREATE TABLE stock_reservations (
    id          bigint       NOT NULL AUTO_INCREMENT,
    order_no    varchar(64)  NOT NULL,
    product_id  bigint       NOT NULL,
    quantity    int          NOT NULL,
    status      ENUM('RESERVED', 'CLAIMED', 'RELEASED') NOT NULL,
    created_at  datetime(6)  NOT NULL,
    updated_at  datetime(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_reservation_order_product (order_no, product_id),
    KEY idx_stock_reservation_status_created (status, created_at)
) ENGINE = InnoDB;
