-- 대사를 거래일 단위로 만든다.
--
-- 문제 ①: 대사가 내부 기록 전체(findAll)를 매번 비교했다. 어제치 PG 정산 파일 하나를 올리면
-- 그제 이전의 모든 내부 기록이 "PG에 없음(INTERNAL_ONLY)"으로 판정돼 예외 큐에 쏟아진다.
-- 운영 3일차에 큐가 수만 건이 되고, 미결건 게이지와 알림이 영구히 울린 채 무의미해진다.
-- 실무 대사는 예외 없이 거래일(또는 정산일) 단위다.
--
-- 문제 ②: 대사 결과에 유니크가 없어 같은 파일을 두 번 올리면 PENDING 행이 두 배가 됐다.
-- 운영자가 실수로 두 번 누르는 것은 가장 흔한 사고인데, 배치는 여러 번 돌려도 결과가 같아야 한다.

ALTER TABLE internal_records
    ADD COLUMN trade_date DATE NULL;

-- 기존 행은 적재 시각(KST)의 날짜로 채운다.
UPDATE internal_records
SET trade_date = DATE(CONVERT_TZ(recorded_at, '+00:00', '+09:00'))
WHERE trade_date IS NULL;

ALTER TABLE internal_records
    MODIFY COLUMN trade_date DATE NOT NULL;

CREATE INDEX idx_internal_record_trade_date ON internal_records (trade_date);

ALTER TABLE reconciliation_results
    ADD COLUMN trade_date DATE NULL;

UPDATE reconciliation_results
SET trade_date = DATE(CONVERT_TZ(reconciled_at, '+00:00', '+09:00'))
WHERE trade_date IS NULL;

ALTER TABLE reconciliation_results
    MODIFY COLUMN trade_date DATE NOT NULL;

-- 같은 거래일의 같은 주문은 판정이 하나뿐이다. 재실행은 덮어쓰고 늘어나지 않는다.
ALTER TABLE reconciliation_results
    ADD CONSTRAINT uk_recon_result_date_order UNIQUE (trade_date, order_no);
