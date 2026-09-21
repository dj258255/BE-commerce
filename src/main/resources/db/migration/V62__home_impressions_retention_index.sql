-- 노출 기록 보존 정책(M7 후속, #198③) — 만료 행을 지우는 질의가 **표 전체를 훑지 않도록** 인덱스를 준다.
--
-- 왜 필요한가: V61의 인덱스는 `(user_id, created_at)` 다 — "이 사용자의 최근 노출"을 위한 모양이고,
-- **보존 정리의 술어(`created_at < cutoff`)에는 못 쓴다**(선두 열이 user_id 라 범위 스캔이 안 된다).
-- 인덱스가 없으면 정리 한 번이 260만 행을 풀스캔한다. 정리 장치를 붙이면서 그 비용도 함께 붙인다.
--
-- 왜 `home_impressions` 를 지우는가: 노출은 **관측 데이터**라 보존 기간이 지나면 쓸모가 사라진다
-- (주문·결제 같은 도메인 상태와 다르다 — 그건 지우지 않는다). 판단과 대가는 ADR-043.

create index idx_home_impressions_created_at on home_impressions (created_at);
