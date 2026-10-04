-- 보상 태스크가 마지막 시도에서 무엇이 일어났는지 남긴다.
--
-- last_outcome    : SUCCEEDED / FAILED_DEFINITE / OUTCOME_UNKNOWN / SKIPPED_UNRESOLVED.
--                   상태 머신(PENDING/DONE/FAILED)과 별개다. 상태는 "다시 시도하나"만 말하고,
--                   "결과를 모르는 실패(OUTCOME_UNKNOWN)"와 "확정 실패(FAILED_DEFINITE)"를 구분하지 못한다.
--                   기존 행은 NULL(그 전 시도의 결과는 알 수 없다).
-- last_attempt_at : 마지막으로 시도한 시각. 시도할 때마다 갱신한다. 기존 행은 NULL.
--
-- 왜 필요한가: 결과를 모르는 취소(전송 뒤 응답 미수신)를 확정 실패로 적으면, 실제로는 PG 에서
-- 취소가 나갔을 수 있는 건을 재시도의 의미가 다른 건과 같은 것으로 취급하게 된다. 또한 미확정
-- 결제(UNKNOWN·IN_PROGRESS) 때문에 보류한 건은 실패가 아니므로 재시도 예산을 태우지 않는다.
ALTER TABLE compensation_tasks
    ADD COLUMN last_outcome VARCHAR(30) NULL,
    ADD COLUMN last_attempt_at DATETIME(6) NULL;
