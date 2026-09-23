-- 블라인드 리뷰를 <쌍 비교>로 넓힌다 (13 문서 실험 14).
--
-- 왜 필요했나: 활성화 조건 1번이 "편집률 중앙값이 <템플릿보다> 낮을 것"인데,
-- V26 구조는 그때 켜진 provider 의 초안 하나만 고정한다. 템플릿과 모델을 재려면
-- provider 를 바꿔 두 번 돌려야 하고, 두 번째 회차는 리뷰어가 이미 답을 아는
-- 상태라 표본이 오염된다. 순서를 강제해 막아 둔 그 오염이 회차 사이로 새는 것이다.
--
-- 그래서 <한 번의 블라인드 답>에 초안 둘을 붙인다. 기존 열은 뜻을 안 바꾼다
-- (model_draft = 켠 provider 의 초안). 템플릿 쪽만 새로 받는다.
ALTER TABLE blind_reviews
    -- 대조군: 지금 쓰고 있는 템플릿 초안. 같은 사실에서 같은 시점에 고정한다.
    ADD COLUMN baseline_draft  TEXT        NULL AFTER model_source,
    ADD COLUMN baseline_source VARCHAR(64) NULL AFTER baseline_draft,
    -- 3단계를 둘 받는다. 어느 쪽이 어느 것인지는 리뷰어에게 안 알린다.
    ADD COLUMN edited_baseline TEXT        NULL AFTER edited_draft,
    ADD COLUMN baseline_edited_at DATETIME(6) NULL AFTER edited_at,
    -- 표시 순서. 항상 같은 쪽을 먼저 보여주면 <먼저 본 것>에 기준이 생긴다.
    -- 공개 시점에 뽑아 고정한다. 다시 열어도 같은 순서라야 채점이 그 화면과 맞는다.
    ADD COLUMN baseline_first  BOOLEAN     NOT NULL DEFAULT FALSE AFTER baseline_source;
