-- 웹훅이 승인 응답보다 먼저 도착하는 경우를 위한 보류 상태.
--
-- 발견 경위: 현업자에게 "동기 요청이 타임아웃으로 늘어지는데 웹훅이 먼저 떨어지면?"이라는
-- 질문을 받고 코드를 열어봤다. 늦게 온 웹훅은 status != UNKNOWN 검사로 멱등하게 막고 있었지만,
-- 먼저 온 웹훅은 PAYMENT_NOT_FOUND 예외로 떨어지고 있었다.
--
-- 왜 그대로 두면 안 되나. 두 안전망이 서로를 무력화한다.
--   (1) receive()가 즉시 200을 반환한다 — PG 재전송이라는 두 번째 그물이 사라진다.
--   (2) 남은 재시도 경로인 Modulith 아웃박스는 republish-outstanding-events-on-restart 라
--       재기동해야 다시 돈다. 즉 재기동 전까지 그 웹훅은 처리되지 않는다.
--
-- 조치: 결제 행이 아직 없으면 예외 대신 PENDING_PAYMENT 로 남기고, 전용 스케줄러가 재시도한다.
-- 아웃박스 재시도에 얹지 않는 이유: 그 경로는 "예외가 났으니 다시"라 사유가 코드에 안 남는다.
-- 이 상태는 "결제 행을 기다리는 중"이라는 뜻이 이름에 있다.
-- status 가 ENUM 컬럼이라 값 목록에 PENDING_PAYMENT 를 더해야 한다.
-- 실 MySQL 통합 테스트에서 "Data truncated for column 'status'" 로 잡혔다.
-- H2 는 문자열로 받아 통과시켜, 이 함정은 실 DB 에서만 드러난다.
ALTER TABLE webhook_events
    MODIFY COLUMN status ENUM('FAILED','PROCESSED','RECEIVED','SKIPPED','PENDING_PAYMENT') NOT NULL;

ALTER TABLE webhook_events
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER fail_reason,
    ADD COLUMN next_retry_at DATETIME(6) NULL AFTER retry_count;

CREATE INDEX idx_webhook_pending
    ON webhook_events (status, next_retry_at);
