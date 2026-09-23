-- 서술을 만든 기록.
--
-- 운영자가 이 문장을 읽고 대사를 확정한다. 나중에 그 확정을 되짚을 때 "그때 화면에 뭐가
-- 떠 있었나"를 답할 수 있어야 한다. 남기지 않으면 사람이 무엇을 보고 판단했는지 영영 모른다.
--
-- 프롬프트 본문은 저장하지 않는다. 사실 묶음에서 결정적으로 재구성되기 때문이다. 대신 사실
-- 개수와 완전성 플래그를 남겨 그때와 지금이 같은 입력인지 대조한다. 재구성되는 것을 또 저장하면
-- 두 곳이 언젠가 갈라지고, 갈라지면 어느 쪽이 맞는지 알 수 없다.
CREATE TABLE narrative_audits (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_no        VARCHAR(64)  NOT NULL,
    source          VARCHAR(100) NOT NULL COMMENT 'template 또는 ollama:모델명 — 모델 버전이 여기 실린다',
    outcome         VARCHAR(40)  NOT NULL COMMENT 'narrated/abstained/unsourced_figures/no_facts',
    output          TEXT         NULL     COMMENT '실제로 나간 문장. 기권·폐기면 NULL',
    fact_count      INT          NOT NULL,
    facts_complete  BOOLEAN      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_narrative_audit_order (order_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 사람이 둘 중 어느 쪽이 나은지 고른 기록. 어느 쪽이 모델인지 모른 채로 고른다.
--
-- 처음에는 길이·기권·출처 없는 숫자로 쟀는데, 앞의 둘은 품질이 아니다. 특히 길이가 짧아진 것을
-- 개선으로 읽은 것은 방향이 틀렸다 — 평가자가 긴 답을 선호하는 편향이 알려져 있어서, 짧아진 것을
-- 좋아졌다고 읽을 근거가 없다. 둘을 나란히 놓고 고르게 하는 쌍 비교가 절대 점수보다 사람 판단과
-- 잘 맞는다.
--
-- 제시 순서를 무작위로 하고 그 순서를 함께 남긴다(source_a/source_b). 순서 효과를 나중에 뺄 수 있다.
CREATE TABLE narrative_preferences (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(64)  NOT NULL,
    source_a    VARCHAR(100) NOT NULL COMMENT 'A 자리에 놓인 것. 고르기 전에는 사람에게 안 보인다',
    source_b    VARCHAR(100) NOT NULL,
    text_a      TEXT         NOT NULL,
    text_b      TEXT         NOT NULL,
    choice      VARCHAR(8)   NULL     COMMENT 'A / B / TIE. 억지로 고르게 하지 않는다',
    reviewer    VARCHAR(100) NULL,
    created_at  DATETIME(6)  NOT NULL,
    chosen_at   DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
