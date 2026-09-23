-- 이미 정산된 뒤에 온 취소를 차기 정산에서 회수하기 위한 조정 항목.
--
-- 예전에는 settlement.postsettle.cancel 카운터만 올렸다. "몇 건 있었다"는 알지만
-- <어떤 주문을 얼마 조정해야 하는지>는 복구할 수 없었고, 재시작하면 처리할 목록조차 남지 않았다.
-- 원장은 취소 이력을 갖고 있지만 그건 근거지 실행할 일이 아니다.
--
-- 과거 정산은 고치지 않는다. 이미 지급 대상으로 나갔고, 수정하면 그때 무엇을 근거로 얼마를 줬는지
-- 추적할 수 없게 된다. 대신 차기 정산에 음수로 반영한다.
CREATE TABLE settlement_adjustments (
    id                     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no               VARCHAR(64)  NOT NULL,
    payment_id             BIGINT       NOT NULL,
    original_settlement_id BIGINT       NOT NULL COMMENT '회수 대상이 나간 원 정산',
    cancel_seq             INT          NOT NULL COMMENT '결제 도메인이 부여한 취소 순번',
    adjustment_amount      BIGINT       NOT NULL COMMENT '회수액. 음수로 저장한다',
    status                 VARCHAR(20)  NOT NULL COMMENT 'PENDING / APPLIED / REVIEW_REQUIRED',
    applied_settlement_id  BIGINT       NULL,
    created_at             DATETIME(6)  NOT NULL,
    applied_at             DATETIME(6)  NULL,
    -- 같은 취소가 재배달돼도 조정이 두 번 생기지 않는다.
    CONSTRAINT uk_settlement_adjustment_order_seq UNIQUE (order_no, cancel_seq),
    KEY idx_settlement_adjustment_status (status)
);

-- 정산 항목이 어느 정산에 들어갔는지. 정산된 뒤 취소가 오면 어느 지급에서 잘못 나갔는지 알아야 한다.
ALTER TABLE settlement_items
    ADD COLUMN settlement_id BIGINT NULL;
