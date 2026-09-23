-- 대사 수기 확정에 사유를 남긴다 (ADR-008 Phase 1).
--
-- 기존에는 resolveManually()가 상태만 MANUALLY_RESOLVED로 전이하고,
-- "누가/왜 확정했는지는 감사 로그로 남긴다"고 주석에 적어 뒀으나 감사 로그 배선이 없었다.
-- 그래서 조사 결과가 어디에도 남지 않아 같은 패턴이 와도 매번 처음부터 조사했다.
--
-- 원인을 코드(집계 가능)와 자유 서술(새 원인 수용) 두 칸으로 나눠 받는다.
-- 코드만이면 새 원인을 못 담고, 자유 서술만이면 반복 패턴을 셀 수 없다.
alter table reconciliation_results
    add column resolved_by   varchar(100) null comment '확정한 운영자',
    add column resolve_cause varchar(40)  null comment '원인 코드(ResolveCause)',
    add column resolve_note  varchar(500) null comment '자유 서술 — OTHER면 필수',
    add column resolved_at   datetime(6)  null comment '확정 시각';

-- 원인 분포를 세는 것이 Phase 1의 목적이다. 분류·원인으로 묶어 집계한다.
create index idx_recon_resolve_cause on reconciliation_results (resolve_cause, result);
