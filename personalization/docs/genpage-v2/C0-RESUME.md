# C0 이어서 할 곳 (2026-09-25 보류 → 2026-09-26 완료)

## 2026-09-26 완료

- origin/main 을 병합한 뒤(96커밋 뒤처져 있었다) 1차 검토 지적 1~7 을 다시 대조했다. 코드(`simulate.py`)는 보류 커밋(a1e9432)에서 이미 새 구조였다
  - 2쪽 문맥: SEP_PAGE 바로 앞에 세션 이벤트
  - `prev_page`: 행 · 상품 토큰만
  - `pages.npz`: `prev_tokens` · `prev_offsets` 저장, SEP_PAGE 끝 검사
  - 가격: 요청 시각 이전 거래만
- 실패하던 두 테스트는 **테스트 쪽이 틀렸다**
  - (a) "앞 쪽 토큰이 ctx 에 없다"는 세션 이벤트가 앞 쪽에서 클릭한 상품이라 올바른 구현도 통과할 수 없다. 행 토큰은 0번, 상품은 세션 이벤트로 1번까지로 고쳤다. 옛 버그(앞 쪽 페이지를 SEP_PAGE 뒤 문맥에 붙이던 것)는 여전히 잡는다
  - (b) "기준 시각 뒤 거래를 떨어뜨린다"의 둘째 거래가 기준보다 앞이었다. 기준 뒤(08-15)로 옮겨 기준을 무시하는 구현이면 실패하게 했다
- 테스트 119개 통과

| 실행 | 사용자 · 페이지 · 노출 | 반응(안 봄 · 지나침 · 클릭 · 구매) | 행 0 → 5 클릭률 | 위치 0 → 7 클릭률 | 페이지 보상 p10 / p50 / p90 | 2쪽 비율 |
|---|---|---|---|---|---|---|
| truth-shuffle 2000 × 2(정답으로 만든 페이지, 상한 확인용) | 2,206 · 2,295 · 6,850 | 0.187 · 0.067 · 0.099 · 0.646 | 0.883 → 0.338 | 0.783 → 0.500 | 1.0 / 5.8 / 12.0 | 0.040 |
| ckpt `b-base-full` 300 × 1(운영 정책) | 300 · 388 · 18,584 | 0.467 · 0.461 · 0.062 · 0.010 | 0.127 → 0.042 | 0.101 → 0.057 | −3.6 / −0.5 / 4.0 | 0.293 |

- 경계: truth-shuffle 2쪽 89개 중 3개는 1쪽이 빈 페이지(행 없음)였던 사용자다. 아무것도 못 본 사용자가 2쪽으로 넘어간다. 1쪽이 비면 2쪽을 만들지 않을지는 C1 에서 노출을 쓸 때 정한다
- 남은 것: WBC(`wbc.py`)가 이 `pages.npz`(`token_reward` 포함)를 읽게 연결하는 일은 #301 에서 한다

---

## 보류 당시 기록(2026-09-25)

ML 범위를 줄이면서 멈췄다(PLAN '선을 넘기 위한 순서'). 백엔드 · 포트폴리오 뒤에 ML 을 끝까지 할 때 여기서 잇는다.

- 상태: reward.py · simulate.py · 테스트 구현됨. 아래 1차 검토 지적은 **고치지 못하고** 멈췄다
- **테스트 2개가 실패하는 상태로 보관했다**(수정 도중 멈춰 새 테스트가 새 구조를 기대한다). 실패 목록:
  - `test_page_two_places_actions_in_history_and_page_prefix_after_sep_page (genpage2.tests.test_simulate.PageTwoTest.test_page_two_places_actions_in_history_and_page_prefix_after_sep_page)`
  - `test_reference_time_drops_later_transactions (genpage2.tests.test_simulate.PriceIndexTest.test_reference_time_drops_later_transactions)`
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
