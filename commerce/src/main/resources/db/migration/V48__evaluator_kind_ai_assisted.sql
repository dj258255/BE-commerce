-- 평가 주체에 <b>AI 보조를 받은 사람</b>을 더한다.
--
-- 2026-09-09 에 사람 판정 세 건을 저장한 뒤, 그 수정본과 메모가 외부 AI 의 도움으로
-- 작성됐다는 것을 심사자가 밝혔다. <b>독립적인 사람 평가가 아니다.</b>
--
-- HUMAN 으로 두면 전환 조건을 그것으로 채우게 된다. 조건이 묻는 것은 <b>사람 심사자가
-- 혼자</b> 초안을 얼마나 고쳐야 하는지다. 보조를 받은 편집량은 같은 질문의 답이 아니다.
--
-- 그리고 <b>경과 시간도 뜻이 달라진다.</b> revealed_at 에서 edited_at 까지에 외부 AI 에
-- 문의한 시간이 들어간다. 작업 시간으로 읽으면 안 된다.
--
-- 기존 값은 안 건드린다. 세 건은 아래에서 따로 옮긴다.
ALTER TABLE fraud_draft_reviews
    MODIFY COLUMN evaluator_kind VARCHAR(24) NOT NULL DEFAULT 'HUMAN'
        COMMENT 'HUMAN 만 전환 조건을 채운다. AI · HUMAN_AI_ASSISTED 는 참고 자료다';
