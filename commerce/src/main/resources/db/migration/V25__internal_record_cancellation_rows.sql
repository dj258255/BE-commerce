-- 취소를 대사 스냅샷에 <별도 행>으로 쌓기 위한 스키마 변경 (ADR-013).
--
-- 발견 경위: 승인 → 대사 확정 → 부분취소 순으로 실제 실행해 재현했다.
--   ① 10,000원 승인 → 내부 스냅샷 10,000
--   ② 대사 실행    → MATCHED로 확정
--   ③ 3,000원 취소 → 내부 스냅샷 7,000, 그런데 <확정된 판정은 10,000 그대로>
-- 확정된 결론과 현재 스냅샷이 갈라진다. 취소가 며칠 뒤에 오면 그 날짜를 다시 대사할 계기가
-- 없어 아무도 모른 채 남는다.
--
-- 원인: applySettleableBalance가 원 거래일 행의 금액을 덮어썼다.
-- 그러면 (1) 이미 나간 판정이 사후에 무효가 되고, (2) PG가 환불을 <취소일 파일에 별도 행>으로
-- 보내는 실제 형태와 어긋나며(그날 대사에서 EXTERNAL_ONLY로 잡힌다),
-- (3) 원장은 역분개만 하는데 대사 스냅샷만 덮어쓰는 비일관이 생긴다.
--
-- 그래서 승인과 취소를 각각의 거래일에 별도 행으로 쌓는다.
--   8/28  ord-1  +10,000  seq=0   (승인)
--   8/30  ord-1   -3,000  seq=1   (취소)  ← 8/28은 건드리지 않는다

-- 취소 순번. 승인은 0, 취소는 PaymentCanceledEvent.cancelSeq를 그대로 쓴다.
-- 기존 행은 전부 승인이므로 0이 맞다.
ALTER TABLE internal_records
    ADD COLUMN seq INT NOT NULL DEFAULT 0 COMMENT '0=승인, 1..N=취소 순번(ADR-013)';

-- 주문당 한 행이던 제약을 (주문, 순번)으로 바꾼다.
-- 제약을 <없애지> 않는 것이 중요하다 — 중복 적재를 막던 장치라 그대로 두면 같은 취소가
-- 두 번 들어온다. 순번을 더해 "같은 취소는 여전히 한 번만"을 유지한다.
ALTER TABLE internal_records DROP INDEX uk_internal_record_order;
ALTER TABLE internal_records
    ADD CONSTRAINT uk_internal_record_order_seq UNIQUE (order_no, seq);
