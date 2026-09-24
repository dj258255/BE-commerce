# C0 이어서 할 곳 (2026-09-25 보류)

ML 범위를 줄이면서 멈췄다(PLAN '선을 넘기 위한 순서'). 백엔드 · 포트폴리오 뒤에 ML 을 끝까지 할 때 여기서 잇는다.

- 상태: reward.py · simulate.py · 테스트 구현됨. 아래 1차 검토 지적은 **고치지 못하고** 멈췄다
- 검증 명령: `cd personalization && $PY -m unittest discover -s genpage2/tests -t .`

## 남은 검토 지적(1차)

`genpage2/simulate.py` · `tests/test_simulate.py` 안에서만 고쳐라. 커밋하지 마라. 끝나면 `.delegate/result-c0.md` 끝에 "## 1차 수정" 절.

## 반드시: 2쪽 프롬프트 구조

원문 쪽 나누기: **세션 행동은 이력의 최근 이벤트로**, **앞 쪽 페이지 토큰은 SEP_PAGE 뒤 이어 쓰기(prefix)** 로 들어간다. 지금 코드는

- `request.ctx_tokens = user.ctx_tokens + prev_tokens` 로 앞 쪽 토큰과 세션 이벤트를 **SEP_PAGE 뒤에** 붙이고,
- 같은 `prev_tokens` 를 `prev_page` 로도 넘겨 디코더가 SEP_PAGE 뒤에 **한 번 더** 붙인다(두 번 들어간다),
- `context.view` 는 SEP_HISTORY ~ SEP_PAGE 사이만 이벤트로 읽으므로, `full` 이 아닌 수준의 체크포인트에서는 SEP_PAGE 뒤의 세션 이벤트가 **사라진다**,
- `prev_page` 에 ACT · AGO · PRICE 토큰이 섞여 디코더가 그것을 앞 쪽 페이지 토큰으로 이어 쓴다.

바꿀 것:
1. 2쪽 문맥 = 1쪽 문맥의 **SEP_PAGE 바로 앞**(= 가장 최근 이벤트 자리)에 세션 이벤트 `[상품][ACT_CLICK|ACT_VIEW][AGO_0-3][PRICE_구간]` 를 끼운 것. 콘텐츠 행 번호도 같은 자리에. 이력이 60개를 넘으면 가장 오래된 이벤트부터 빠지는 건 `context.truncate` 가 한다(여기서 자르지 마라)
2. `prev_page` = 앞 쪽의 **행 · 상품 토큰만**(`[행][상품…]…`, EOS 없음). 세션 이벤트를 넣지 마라
3. `pages.npz` 에 `prev_tokens` · `prev_offsets` 를 더한다(1쪽은 빈 구간). 2쪽의 학습 시퀀스는 `ctx_tokens + prev_tokens + page_tokens` 가 된다. `ctx_tokens` 는 반드시 SEP_PAGE 로 끝나야 한다(assert)
4. 테스트: 2쪽 문맥에서 세션 이벤트가 SEP_HISTORY ~ SEP_PAGE 사이 **맨 뒤**에 있다, SEP_PAGE 뒤에는 아무것도 없다, `prev_tokens` 에 ACT · AGO · PRICE 가 없다, 앞 쪽 토큰이 한 번만 들어간다. `context.view(..., "items")` 로 2쪽 문맥을 봐도 세션 이벤트의 상품이 남는다
5. `CkptSource` 는 `prev_page` 를 그대로 디코더에 넘긴다(이미 그렇다면 유지)

## 같이

6. `PriceIndex.from_transactions` 는 기준 시각 이전 전체 가격 중앙값을 쓴다. 세션 이벤트의 가격은 **요청 시각 r 이전** 거래로만 정하라(요청 뒤 가격이 섞이지 않게). r 별로 계산이 부담이면 r 이전 마지막 관측 가격, 없으면 전체 r 이전 중앙값
7. 기존 테스트를 지우거나 약하게 하지 마라

확인: `cd personalization && $PY -m unittest discover -s genpage2/tests -t .` 통과. `GENPAGE_DATA=... $PY -m genpage2.simulate --mode validate --source truth-shuffle --customers 2000 --dates 2 --out ../.delegate/out-c0` 다시 돌려 stats 를 result 에 적어라.
