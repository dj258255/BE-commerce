-- 미확정 복구가 확정하지 못한 시도를 남긴다(#248).
--
-- recovery_attempts: PG 가 "진행 중"이라고 답했거나 조회가 실패해 UNKNOWN 으로 남은 횟수. 확정되면 더 오르지 않는다.
-- recovery_next_at : 다음에 다시 물어볼 시각. backoff 정책만 채운다. NULL 이면 바로 대상이다.
--
-- 왜 필요한가: 복구 쿼리는 오래된 순으로 청크만큼 읽는다. 확정하지 못한 건이 그 앞자리에 청크만큼 쌓이면
-- 매 주기 같은 건만 다시 읽고 뒤의 건은 차례가 오지 않는다. 다음 시각을 뒤로 밀어 앞자리를 비운다.
ALTER TABLE payments
    ADD COLUMN recovery_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN recovery_next_at DATETIME(6) NULL;
