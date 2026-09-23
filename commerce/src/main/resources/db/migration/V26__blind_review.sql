-- 블라인드 리뷰 (ADR-014) — 초안이 <쓸 만한지>를 재는 표본.
--
-- 왜 이 순서로 저장하나: 사람이 모델 초안을 보기 <전에> 자기 답을 먼저 쓴다.
-- 보고 나서 쓰면 그 문장에 끌려가(앵커링) "고칠 게 없었다"와 "고칠 생각이 안 났다"가
-- 구분되지 않는다. 이건 대사 분류기에서 이미 겪는 문제고, 그래서 그 수치를
-- 정확도가 아니라 "일치율"이라고 부르고 있다.
--
-- blind_reply 가 NULL 인 채로 model_draft 를 공개할 수 없도록 애플리케이션이 막는다.
-- 순서가 이 실험의 유일한 방법론적 근거다.
CREATE TABLE blind_reviews (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    recon_result_id   BIGINT       NOT NULL,
    order_no          VARCHAR(64)  NOT NULL,
    reviewer          VARCHAR(64)  NOT NULL,

    -- 1단계: 사실만 보고 사람이 직접 쓴 답
    blind_reply       TEXT         NULL,
    blind_at          DATETIME(6)  NULL,

    -- 2단계: 그 뒤 공개된 모델 초안 (공개 시점에 고정한다 — 모델은 매번 다르게 쓴다)
    model_draft       TEXT         NULL,
    model_source      VARCHAR(64)  NULL,
    revealed_at       DATETIME(6)  NULL,

    -- 3단계: 모델 초안을 발송 가능하게 고친 결과
    edited_draft      TEXT         NULL,
    edited_at         DATETIME(6)  NULL,

    created_at        DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    -- 한 사람이 같은 건을 두 번 리뷰하면 두 번째는 이미 답을 아는 상태다.
    -- 표본이 오염되므로 DB에서 막는다.
    UNIQUE KEY uk_blind_review_recon_reviewer (recon_result_id, reviewer),
    KEY idx_blind_review_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
