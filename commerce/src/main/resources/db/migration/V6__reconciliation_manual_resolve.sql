-- 대사 결과에 수기 확정 상태 추가 — 어드민이 PENDING 예외 큐를 확인 후 종결(MANUALLY_RESOLVED).
-- 엔티티 ReconStatus enum 3값과 정확히 일치해야 ddl-auto=validate가 통과한다.

    alter table reconciliation_results
        modify status enum ('AUTO_RESOLVED','PENDING','MANUALLY_RESOLVED') not null;
