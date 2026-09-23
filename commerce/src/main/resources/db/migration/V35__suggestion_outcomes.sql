-- 제안이 사람의 확정과 맞았는지를 <b>행으로</b> 남긴다.
--
-- 왜 필요한가: 자동 확정(사다리 3단)의 조건이 "그 유형의 실측 오류율이 선언한 한도 안"인데,
-- 그 수치를 계산할 방법이 없었다. 승인 여부를 프로메테우스 카운터로만 세고 있었고
--   (1) 재시작하면 사라지고
--   (2) 태그가 outcome·blind 뿐이라 <b>원인 유형별로 안 갈린다</b>
-- 유형별 오류율을 못 내면 3단은 영영 못 켠다. 2단(사람이 승인)이 3단의 데이터를 만든다.
CREATE TABLE suggestion_outcomes (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    recon_result_id   BIGINT       NOT NULL,
    suggested_cause   VARCHAR(40)  NULL     COMMENT '모델·규칙이 제안한 원인. 기권했으면 NULL',
    chosen_cause      VARCHAR(40)  NOT NULL COMMENT '사람이 확정한 원인',
    outcome           VARCHAR(16)  NOT NULL COMMENT 'accepted / rejected / abstained / no_suggestion',
    -- 제안을 보여준 뒤 고르게 했는지, 가린 채로 먼저 고르게 했는지.
    -- 보여주면 앵커링이 생겨 일치율이 올라간다. 섞어서 세면 그 수치를 못 믿는다.
    blind             BOOLEAN      NOT NULL,
    resolved_by       VARCHAR(100) NULL,
    suggested_at      DATETIME(6)  NULL,
    resolved_at       DATETIME(6)  NOT NULL,
    -- 같은 대사 건이 두 번 확정되지 않는다. 확정은 한 번뿐이므로 행도 한 번뿐이어야 한다.
    UNIQUE KEY uk_suggestion_outcome_recon (recon_result_id),
    -- 유형별 오류율 집계가 이 표의 존재 이유다. 그 조회를 인덱스로 받친다.
    KEY idx_suggestion_outcome_type (suggested_cause, blind, outcome)
) COMMENT='제안과 사람 확정의 일치 기록. 자동 확정 승격 판단의 근거가 된다';
