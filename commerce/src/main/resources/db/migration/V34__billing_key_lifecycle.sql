-- 빌링키에 수명주기를 둔다.
--
-- 빌링키는 카드번호의 대체물이다. 카드가 재발급되거나 고객이 구독을 해지하면 그 대체물도
-- 같이 죽어야 하는데, 지금까지는 발급만 있고 폐기가 없었다. 그래서 해지한 고객의 결제 수단을
-- 계속 들고 있었고, 카드가 죽은 뒤에도 같은 키가 ACTIVE 로 남아 다음 구독에서 또 실패했다.
--
-- 지우지 않고 상태로 남긴다. 지우면 왜 못 쓰게 됐는지를 나중에 답할 수 없다.
-- 기존 행은 전부 ACTIVE 다 — 폐기된 적이 없기 때문이다.
ALTER TABLE billing_keys
    ADD COLUMN status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' AFTER user_id,
    ADD COLUMN revoke_reason VARCHAR(100) NULL     AFTER status,
    ADD COLUMN revoked_at    DATETIME(6)  NULL     AFTER revoke_reason;
