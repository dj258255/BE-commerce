-- FDS 심사 초안의 블라인드 비교 (V26 blind_reviews 와 같은 구조).
--
-- 왜 또 만드나: V26 은 대사 상담 초안용이라 recon_result_id 에 묶여 있다. 심사 초안은
-- fraud_review_id 에 묶이므로 그 표를 재사용하면 <b>두 실험의 표본이 한 표에 섞인다.</b>
-- 섞이면 "상담 초안이 좋아졌다" 와 "심사 초안이 좋아졌다" 를 나중에 못 가른다.
--
-- <b>순서가 이 실험의 유일한 방법론적 근거다.</b> 사람이 초안을 보기 전에 자기 답을 먼저 쓴다.
-- 보고 나서 쓰면 그 문장에 끌려가(앵커링) "고칠 게 없었다" 와 "고칠 생각이 안 났다" 가
-- 구분되지 않는다.
CREATE TABLE fraud_draft_reviews (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    fraud_review_id    BIGINT       NOT NULL,
    reviewer           VARCHAR(64)  NOT NULL,

    -- 1단계: 사실만 보고 심사자가 직접 쓴 메모
    blind_reply        TEXT         NULL,
    blind_at           DATETIME(6)  NULL,

    -- 2단계: 그 뒤 공개하는 초안 둘. 어느 쪽이 모델인지 심사자에게 안 알린다.
    -- 공개 시점에 고정한다. 모델은 부를 때마다 다르게 쓴다.
    model_draft        TEXT         NULL,
    model_source       VARCHAR(64)  NULL,
    baseline_draft     TEXT         NULL,
    baseline_source    VARCHAR(64)  NULL,
    -- 표시 순서. 항상 같은 쪽을 먼저 보여주면 <먼저 본 것>에 기준이 생긴다.
    -- 공개 시점에 뽑아 고정한다. 다시 열어도 같은 순서라야 채점이 그 화면과 맞는다.
    baseline_first     BOOLEAN      NOT NULL DEFAULT FALSE,
    revealed_at        DATETIME(6)  NULL,

    -- 3단계: 각각을 쓸 만하게 고친 결과
    edited_draft       TEXT         NULL,
    edited_at          DATETIME(6)  NULL,
    edited_baseline    TEXT         NULL,
    baseline_edited_at DATETIME(6)  NULL,

    created_at         DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    -- 한 사람이 같은 건을 두 번 보면 두 번째는 이미 답을 아는 상태다. 표본이 오염되므로
    -- DB 에서 막는다. V26 이 같은 이유로 걸어 둔 제약이다.
    CONSTRAINT uk_fraud_draft_review_reviewer UNIQUE (fraud_review_id, reviewer),
    KEY idx_fraud_draft_review_created (created_at)
);
