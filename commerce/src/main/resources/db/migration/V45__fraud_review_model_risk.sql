-- 심사 항목에 모델 점수를 남긴다.
--
-- 왜 필요했나: docs/27 5-1 절이 "지금 켤 수 있는 쓰임은 큐 정렬 하나" 라고 적어 뒀는데
-- <b>정작 정렬을 안 하고 있었다.</b> 조회가 findByStatus 그대로여서 모델이 아무것도 안
-- 바꾸고 있었다. 구성을 정한 것과 기능이 도는 것은 다른 일이다.
--
-- <b>집합은 규칙이 정하고 순서만 모델이 정한다.</b> 이 컬럼이 큐에 넣고 빼는 데는 안 쓰이고
-- 정렬에만 쓰인다. 그래서 경보율이 안 늘고, 순서만 쓰므로 기저율에 안 휘둘린다.
-- P@10 과 P@30 이 100% 라 상위 구간의 순서는 믿을 만하다는 것이 그 근거다.
--
-- NULL 인 이유는 둘이다. 홀드아웃에 들었거나(점수를 아예 안 낸다), 채점이 실패했거나.
-- MySQL 은 DESC 정렬에서 NULL 을 뒤로 보내므로 점수 없는 건이 아래로 간다.
ALTER TABLE fraud_reviews
    ADD COLUMN model_risk DOUBLE NULL COMMENT '섀도 모델 점수. 정렬에만 쓰고 큐 적재에는 안 쓴다';

-- 정렬이 쓰는 인덱스. 상태로 좁힌 뒤 점수로 정렬한다.
CREATE INDEX idx_fraud_review_status_risk ON fraud_reviews (status, model_risk);
