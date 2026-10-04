-- 정산 항목의 lost update 를 막는다(ADR-073:59).
--
-- 배치의 settle(CONFIRMED→SETTLED)과 취소 리스너의 reflectCancellation(→CANCELED/금액 변경)이
-- 같은 settlement_items 행을 동시에 쓰면 늦은 쪽의 변경이 조용히 사라진다. 정산은 돈이므로 그 유실은
-- 이중 지급이거나 누락이다. Hibernate @Version 낙관적 락으로 늦게 커밋하는 쪽이 예외로 실패하게 한다.
--
-- 기존 행은 DEFAULT 0 으로 채운다(엔티티의 @Version long 과 짝).
ALTER TABLE settlement_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
