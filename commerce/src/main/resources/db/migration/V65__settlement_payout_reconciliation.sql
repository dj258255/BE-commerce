-- 외부 지급 report를 내부 정산과 연결하고, MATCHED 이외의 결과는 PAID_OUT으로 승격하지 않는다.
-- 기존 행은 외부 report가 없으므로 legacy reference만 채운다. 이 값은 지급 확정 근거가 아니다.
ALTER TABLE settlements
    ADD COLUMN payout_instruction_reference VARCHAR(100) NULL,
    ADD COLUMN payout_reconciliation_status ENUM(
        'MATCHED', 'PENDING', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH',
        'UNMATCHED_SETTLEMENT', 'UNMATCHED_PAYOUT', 'DUPLICATE_PAYOUT_REFERENCE') NULL,
    ADD COLUMN payout_report_reference VARCHAR(100) NULL,
    ADD COLUMN payout_report_currency VARCHAR(3) NULL,
    ADD COLUMN payout_report_amount BIGINT NULL,
    ADD COLUMN payout_reconciled_at DATETIME(6) NULL,
    ADD COLUMN payout_reconciliation_reason VARCHAR(200) NULL;

UPDATE settlements
SET payout_instruction_reference = CONCAT('LEGACY-SETTLEMENT-', id)
WHERE payout_instruction_reference IS NULL;

ALTER TABLE settlements
    MODIFY COLUMN payout_instruction_reference VARCHAR(100) NOT NULL,
    ADD CONSTRAINT uk_settlement_payout_instruction_reference
        UNIQUE (payout_instruction_reference);
