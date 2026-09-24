-- A/B 실험의 노출 기록(#256). 홈 1쪽 노출에 실험과 변형을 남긴다. 실험이 없으면 null.
-- 분석은 이 두 열로 노출을 변형별로 나누고, 노출된 상품에 한해 클릭·구매를 귀속한다(tools/ab_analysis.py).
ALTER TABLE home_impressions
    ADD COLUMN experiment VARCHAR(40) NULL,
    ADD COLUMN variant VARCHAR(20) NULL;

CREATE INDEX idx_home_impressions_experiment ON home_impressions (experiment, variant, user_id);
