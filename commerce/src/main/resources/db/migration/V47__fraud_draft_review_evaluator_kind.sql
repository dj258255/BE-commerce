-- 블라인드 비교 기록에 <b>누가 평가했는지</b>를 남긴다.
--
-- 전에는 `reviewer` 문자열 하나뿐이라 사람이 판정한 것과 모델이 판정한 것이 한 표에 섞였다.
-- 섞이면 켤 조건을 잴 때 둘을 못 가른다. 심사 초안의 전환 조건은 <b>사람 심사자가</b> 초안을
-- 얼마나 고쳐야 하는지를 묻는 것이라, 모델이 고친 양은 그 조건을 채울 수 없다.
--
-- 기본값을 HUMAN 으로 두어 <b>이미 쌓인 기록은 그대로 사람 평가</b>로 남는다. 상담 초안 쪽
-- 표(V26·V41)는 건드리지 않는다.
ALTER TABLE fraud_draft_reviews
    ADD COLUMN evaluator_kind VARCHAR(16) NOT NULL DEFAULT 'HUMAN'
        COMMENT '평가 주체. HUMAN 만 전환 조건을 채운다. AI 는 참고 자료다';

-- 켤 조건을 잴 때 사람 것만 골라야 하므로 조회 조건에 들어간다.
CREATE INDEX idx_fraud_draft_review_evaluator ON fraud_draft_reviews (evaluator_kind);
