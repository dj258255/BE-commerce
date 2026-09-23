-- 원장 중복 판정에 원천 순번(source_seq)을 더한다.
--
-- 문제: 중복 판정 키가 (tx_type, source_type, source_id) 뿐이라 원천 하나에 사건이 여러 번 일어나는
-- 경우를 구분하지 못했다. 취소가 정확히 그렇다. 20,000원 결제를 5,000원씩 두 번 부분취소하면
-- 두 번째 역분개가 "이미 분개함"으로 판정되어 원장에 남지 않는다.
--
-- 그 결과가 조용하다는 점이 특히 나쁘다. 각 분개는 차대가 맞으므로 자가 검증(차변 합 = 대변 합)에
-- 걸리지 않는다. 거래 자체가 통째로 빠지는 것이라 매출만 과대계상된 채 아무 경보도 울리지 않는다.
--
-- 해결: 원천 순번을 키에 넣는다. 사건이 한 번뿐인 원천(승인·분쟁)은 0을 쓰고, 취소는 결제의
-- 취소 순번을 쓴다. 같은 취소의 재배달은 순번이 같아 여전히 멱등하다.
ALTER TABLE ledger_transactions
    ADD COLUMN source_seq INT NOT NULL DEFAULT 0;

ALTER TABLE ledger_transactions
    DROP INDEX uk_ledger_tx_source;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT uk_ledger_tx_source
        UNIQUE (tx_type, source_type, source_id, source_seq);
