-- 분쟁 승패 확정(WON/LOST) 동시성 레이스를 막기 위해 낙관적 락(@Version) 컬럼을 추가한다.
-- 두 어드민이 같은 OPEN 분쟁을 동시에 서로 다른 결과로 확정하려 하면 나중 커밋이 버전 충돌로 실패한다.
alter table disputes
    add column version bigint not null default 0;
