-- #369 멱등 처리권에 만료(리스)와 버전을 둔다.
-- 요청 도중 프로세스가 죽으면 PROCESSING 행이 남아 같은 키가 expires_at(15일)까지 409 를 받았다.
-- lease_until 이 지난 PROCESSING 행은 조건부 UPDATE 로 한 요청만 넘겨받고, version 이 늦게 끝난
-- 원래 요청의 덮어쓰기를 막는다. 이미 있는 행은 만든 시각 + 3분(기본 리스)으로 채운다.
ALTER TABLE idempotency_keys
    ADD COLUMN lease_until datetime(6) NULL,
    ADD COLUMN version bigint NOT NULL DEFAULT 0;

UPDATE idempotency_keys SET lease_until = created_at + INTERVAL 3 MINUTE WHERE lease_until IS NULL;

ALTER TABLE idempotency_keys MODIFY lease_until datetime(6) NOT NULL;
