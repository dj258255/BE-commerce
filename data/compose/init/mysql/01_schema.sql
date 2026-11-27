-- 원본 스키마: BE-commerce(github.com/dj258255/BE-commerce) 커밋 ed3757d의
-- commerce/src/main/resources/db/migration V1~V72에서 데이터 쪽이 읽는 표 9개만 골라 최종 모양으로 합쳤다.
-- 열 이름, 타입, 상태 값(enum), 유일 제약, 외래 키, 인덱스는 원본과 같다.
-- 뺀 열은 docs/source-schema.md에 적었다(개인정보, 지급 대사, 복구 배치 내부 상태 등).
-- 시각 열은 전부 UTC로 쓴다. 영업일(trade_date)만 KST 날짜다. 원본 V21과 같은 규칙이다.

USE becommerce;

-- 정산을 받는 쪽. id 1은 플랫폼 직판(원본 V49).
CREATE TABLE sellers (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_number VARCHAR(20)  NOT NULL,
    legal_name      VARCHAR(200) NOT NULL,
    country_code    CHAR(2)      NOT NULL DEFAULT 'KR',
    status          VARCHAR(24)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_seller_business_number (business_number),
    KEY idx_seller_status (status)
) ENGINE=InnoDB;

CREATE TABLE products (
    product_id    BIGINT       NOT NULL,
    name          VARCHAR(200) NOT NULL,
    price         BIGINT       NOT NULL,
    category_code VARCHAR(40)  NULL,
    brand         VARCHAR(120) NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (product_id),
    KEY idx_products_category (category_code)
) ENGINE=InnoDB;

CREATE TABLE orders (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(64) NOT NULL,
    user_id      BIGINT      NOT NULL,
    total_amount BIGINT      NOT NULL,
    currency     VARCHAR(3)  NOT NULL,
    status       ENUM ('CANCELED','CREATED','EXPIRED','FAILED','PAID','PAYMENT_IN_PROGRESS','PENDING_PAYMENT') NOT NULL,
    version      BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    expires_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_user_id (user_id, id),
    KEY idx_orders_status_expires (status, expires_at),
    KEY idx_orders_status_created (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE order_items (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    unit_price   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB;

-- 결제 한 건. 부분 취소는 balance_amount를 줄이고 cancel_count를 올린다(원본 V19).
CREATE TABLE payments (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_no           VARCHAR(64)  NOT NULL,
    payment_key        VARCHAR(200) NULL,
    pg_provider        VARCHAR(30)  NOT NULL,
    method             VARCHAR(30)  NULL,
    installment_months INT          NOT NULL DEFAULT 0,
    amount             BIGINT       NOT NULL,
    balance_amount     BIGINT       NOT NULL,
    cancel_count       INT          NOT NULL DEFAULT 0,
    status             ENUM ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') NOT NULL,
    unknown_reason     VARCHAR(200) NULL,
    requested_at       DATETIME(6)  NOT NULL,
    approved_at        DATETIME(6)  NULL,
    version            BIGINT       NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payments_status_requested (status, requested_at),
    KEY idx_payments_order (order_no),
    KEY idx_payments_payment_key (payment_key)
) ENGINE=InnoDB;

-- 결제 상태 전이 이력. 한 결제에 여러 행이 생긴다.
CREATE TABLE payment_history (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id   BIGINT       NOT NULL,
    from_status  ENUM ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') NOT NULL,
    to_status    ENUM ('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','UNKNOWN') NOT NULL,
    triggered_by ENUM ('ADMIN','POLLING','RECOVERY_BATCH','USER','WEBHOOK') NOT NULL,
    reason       VARCHAR(500) NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_payment_history_payment (payment_id, created_at),
    CONSTRAINT fk_payment_history_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
) ENGINE=InnoDB;

-- 내부 거래 기록. seq 0은 승인, 1..N은 취소 순번(원본 V25). 취소 금액은 음수로 적는다.
CREATE TABLE internal_records (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64) NOT NULL,
    seq         INT         NOT NULL DEFAULT 0,
    amount      BIGINT      NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    trade_date  DATE        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_internal_record_order_seq (order_no, seq),
    KEY idx_internal_record_trade_date (trade_date)
) ENGINE=InnoDB;

-- 정산 대상 한 건(결제당 하나). 구매 확정 전에는 PENDING_CONFIRMATION.
CREATE TABLE settlement_items (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    payment_id      BIGINT      NOT NULL,
    order_no        VARCHAR(64) NOT NULL,
    seller_id       BIGINT      NOT NULL,
    amount          BIGINT      NOT NULL,
    confirmed_date  DATE        NOT NULL,
    status          ENUM ('PENDING_CONFIRMATION','CONFIRMED','SETTLED','CANCELED') NOT NULL,
    last_cancel_seq INT         NOT NULL DEFAULT -1,
    settlement_id   BIGINT      NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_item_payment (payment_id),
    KEY idx_settlement_items_order_no (order_no),
    KEY idx_settlement_items_status (status, confirmed_date),
    KEY idx_settlement_item_seller (seller_id, status, confirmed_date),
    KEY idx_settlement_item_date_status (confirmed_date, status)
) ENGINE=InnoDB;

-- 판매자별, 통화별, 날짜별 정산 한 건.
CREATE TABLE settlements (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    settlement_date DATE       NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    seller_id      BIGINT      NOT NULL,
    item_count     INT         NOT NULL,
    gross_amount   BIGINT      NOT NULL,
    fee_amount     BIGINT      NOT NULL,
    fee_vat_amount BIGINT      NOT NULL DEFAULT 0,
    net_amount     BIGINT      NOT NULL,
    status         ENUM ('CREATED','PAYOUT_HELD','PAID_OUT') NOT NULL,
    payout_date    DATE        NOT NULL,
    paid_out_at    DATETIME(6) NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_date_currency_seller (settlement_date, currency, seller_id)
) ENGINE=InnoDB;

INSERT INTO sellers (id, business_number, legal_name, country_code, status, created_at, updated_at)
VALUES (1, 'PLATFORM', '플랫폼 직판', 'KR', 'ACTIVE', NOW(6), NOW(6));

-- CDC 계정(3장). Debezium MySQL 커넥터가 요구하는 권한만 준다.
CREATE USER 'debezium'@'%' IDENTIFIED BY 'debezium-local-only';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
