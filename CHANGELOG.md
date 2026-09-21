# 변경 기록

릴리스별로 **사용자에게 무엇이 나갔는가**를 한 곳에 모은다. 설계 결정의 이유는 [ADR](docs/adr)에,
검증 기록은 `docs/performance/`·`personalization/docs/runs/`에 있다 — 이 파일은 그 둘을 대신하지
않고 "무엇이 언제 나갔는가"만 적는다.

> 이 파일은 2026-09-20에 만들었다. 그 이전 릴리스는 GitHub Releases에만 있고 여기로 옮기지 않았다
> (커밋 로그와 ADR이 그 시기의 기록이다). 여기서부터는 릴리스마다 아래에 한 절씩 더한다.

## Unreleased — M2: 개인화 온라인 컨텍스트와 E1 실측

### 추가

- **개인화 온라인 컨텍스트**(`personalization` 모듈, `V59`) — 활동 수집 → 컨텍스트 갱신 → 온라인 읽기.
  `POST /api/v1/personalization/activity`(합성 생성기용) · `GET /api/v1/personalization/context`
  (`expectSeq`·`waitMs`로 **대기 정책**을 정한다)
- **전달 방식 3종**(`app.personalization.transport`) — `IN_PROCESS`(기본) · `KAFKA` · `IN_REQUEST`.
  적용 로직은 하나를 공유하고 **누가 언제 적용하는지**만 다르다
- **첫 인앱 Kafka 컨슈머** — `kafka` 프로파일에서만 뜬다
- **E1 실험 리포트** — `personalization/docs/runs/20260921-e1-신선도-지연/`(8절 형식 + 원자료).
  하네스: `k6/freshness-vs-latency.js` · `tools/run-freshness-vs-latency.sh` ·
  `tools/check-multi-instance.sh` · `tools/freshness_report.py`

### 변경

- **기본 전달 방식이 `KAFKA`로 되돌아왔다.** E1이 (지연·쓰기 비용·다중 인스턴스만 보고) `IN_PROCESS`를
  골랐는데, **E2가 순서 보존을 재서 뒤집었다** — `IN_PROCESS`는 `@Async` 풀이라 같은 사용자의 이벤트가
  겹쳐 항목을 잃는다(33.3% vs `KAFKA` 100%). 정확성이 편의보다 앞선다.
  고치는 길(원자적 저장소)은 [#176](https://github.com/dj258255/BE-commerce/issues/176)
- 개인화 화면의 **목 요약을 실측으로 교체**하고, 측정하지 않은 실험 다섯을 `측정 전`으로 되돌렸다
  (`personalization/web/fixtures/exp-freshness.json`·`exp-consistency.json`·`experiments.json`)

## Unreleased — M2: E2 online/offline 일치율

### 추가

- **E2 실험 리포트** — `personalization/docs/runs/20260921-e2-online-offline-일치율/`(8절 형식 + 원자료).
  하네스: `tools/inject-activities.py`(통제된 주입) · `tools/compare-contexts.py`(offline 재계산 +
  원인 분류) · `tools/run-consistency-experiment.sh` · `tools/consistency_report.py`

### 검증된 것

- 기준 조건 **일치율 100%** — 파이프라인 자체는 로그를 충실히 반영한다
- 순서를 뒤집으면 **0%**(낮은 seq를 영구히 버린다), TTL이 짧으면 **0%**(로그는 안전),
  로그가 `max-items`를 넘으면 **목록 100%·창 집계 60%**
- **중복은 두 층이 막았다** — 입력 중복은 409, Kafka 오프셋을 되감아 **재배달**시켜도 컨텍스트 불변

## Unreleased — 추천 서빙과 과부하 정책 (#126 / M3)

### 추가

- **`recommendation` 모듈** — `GET /api/v1/recommendations`. 개인화 모듈에서 최근 활동을 받아
  **모델에 넣고**, 모델이 못 대답하면 **개인화를 포기하고 인기 상품으로 답한다**(200).
  응답의 `source`가 `MODEL`/`FALLBACK`을 밝히고 `fallbackReason`이 사유를 나눈다
- **과부하 정책 3종**(E3의 독립변수) — `UNBOUNDED`(아무도 거절 안 함) / `BOUNDED`(상한) /
  `ADMISSION`(Little의 법칙으로 대기 예상을 재서 예산을 넘으면 줄에 서지 않는다). **기본 `ADMISSION`**
- **모델은 교체 가능한 dependency** — `ModelClient` 인터페이스 하나. 지금 구현은 실험용 스텁
  (용량·지연을 설정으로 고정)이고, 실제 모델 서버를 붙일 때 바뀌는 것은 그 구현 하나다
- **개인화 read port** — `personalization.RecentActivityFacts`. 내부 저장소를 열지 않고
  **상품 id만** 내보낸다(ADR-018과 같은 방식)

### 측정 (E3)

[리포트](personalization/docs/runs/20260921-e3-과부하-degradation/report.md) · [ADR-037](docs/adr/ADR-037-overload-admission-policy.md)

| 부하 (모델 용량 80/s) | `ADMISSION` | `BOUNDED` | `UNBOUNDED` |
|---:|---:|---:|---:|
| 0.5× | 99.9% / 55ms | 100.0% / 55ms | 100.0% / 55ms |
| 1× | 93.6% / **160ms** | 93.7% / **320ms** | 93.5% / **454ms** |
| 2× | 47.1% / 160ms | 47.1% / 320ms | 46.9% / 454ms |
| 4× | 23.7% / **159ms** | 23.7% / 318ms | 31.4% / 453ms *(달성 212/320 — 생존자 편향)* |

- **정책은 coverage를 사지 못한다** — 세 정책 차이 ±0.3%p. 커버리지는 모델 처리량이 정한다
- **정책은 지연을 산다** — 같은 coverage에서 p95 160 / 320 / 454ms. **부하와 무관하게 고정**이다
  (지연을 정하는 것은 부하가 아니라 정책 파라미터다)
- **무한 큐는 API를 죽인다** — 4×에서 `UNBOUNDED`는 요청을 **받지도 못했다**(달성 212/320).
  그 결과 coverage가 31.4%로 **더 좋아 보인다** — 못 받은 요청이 분모에서 빠져서다

### 검증

- `OverloadGateTest` 5건 · `RecommendationServiceTest` 6건 — 정책별 판정, 거절 시 **모델을 부르지 않음**,
  폴백 사유 분리(REJECTED/TIMEOUT/FAILED), 활동이 없어도 모델은 부른다
- 하네스가 **`achieved`(달성 부하)와 `personalized`(재료가 있었던 비율)**를 함께 낸다 —
  이 둘이 없으면 coverage가 좋아 보이는 방향으로 틀린다(실제로 첫 실행이 그랬다)

## Unreleased — 창 집계를 목록에서 떼어낸다 (#182)

### 변경

- **컨텍스트가 유형별 카운터(`counts`)를 함께 담는다.** 목록은 `max-items`(20)로 잘리지만 집계는
  잘리지 않아야 한다 — 그릇이 하나면 **잘림과 집계가 같이 망가진다.** E2가 `window` 조건에서
  목록 100%인데 집계 60%를 본 이유다. 창은 **컨텍스트의 수명(TTL, 기본 7일)**이다
- **카운터를 목록과 같은 Lua 안에서 갱신한다** — 따로 저장하면 둘이 어긋나는 순간이 생긴다.
  재배달은 병합 전에 걸러지므로 두 번 세어지지 않는다 → **집계도 순서에 무관**하다
- **대조 도구가 창을 유형별 카운터로 센다.** 옛 도구는 `len(online_items) == len(log_rows)`였다 —
  **앱의 그릇을 그대로 쓴 도구**라서 앱만 고치면 좋아진 것을 볼 수 없었다. `truncated`는 불일치
  원인에서 **관찰로 내렸다**(목록이 잘리는 것은 이제 정상 동작이다)
- **정의가 바뀌었으므로 전 조건을 새 도구로 다시 쟀다** — E2 · E2-c · E2-e의 "창 집계 일치율"은
  목록 길이 기준이고, [E2-f](personalization/docs/runs/20260921-e2f-창집계-재측정/report.md)부터
  유형별 카운터 기준이다. **섞어 놓으면 거짓말이 된다**

### 검증

- `window` 조건 창 집계 일치율 **60.0% → 100.0%** (컨텍스트 100%)
- 전 조건 재측정: `baseline`·`disorder`·`duplicate`·`order-*`·`redelivery`·`window` **100%**,
  `late` **95%**(behind 5% · dropped 5%), `ttl` 0%(만료는 설계)
- `ContextStoreConcurrencyTest` **11건**(+4) — 창 집계가 목록 잘림을 넘어선다(목록 20 / 집계 30),
  유형별로 센다, 재배달은 두 번 세지 않는다, 순서가 뒤집혀도 같다

## Unreleased — 컨텍스트 적용을 원자적으로 (#176)

### 변경

- **`ContextStore.apply`가 원자적이 됐다.** 읽기·비교·병합·쓰기를 **Lua 스크립트 한 번**으로 옮겼다 —
  자바에서 나눠 하던 동안에는 같은 사용자의 이벤트가 겹치면 한쪽 쓰기가 다른 쪽을 덮었다
  (E2가 항목 유실로 관측). 이제 병합이 스크립트 안에서 끝나므로 **전송 방식과 무관하게** 덮이지 않는다
- **그것만으로는 부족했다.** 같은 조건 재측정: `order-in-process` **33.3% → 83.3%**(`order-kafka` 100% 유지).
  남은 유실의 원인은 **적용 순서 역전**이고, 그건 원자성이 아니라 순서의 문제다 →
  [E2-b 리포트](personalization/docs/runs/20260921-e2b-원자화-재측정/report.md) ·
  후속 [#178](https://github.com/dj258255/BE-commerce/issues/178)
- **기본 전달 방식은 `KAFKA` 유지.** 근거가 바뀌지 않았다. 원자화는 필요조건이지 충분조건이 아니다

### 검증

- `ContextStoreConcurrencyTest`(신규, 실 Redis Testcontainer) 5건 — 동시 적용 후에도 **최대 seq가
  덮이지 않고**(64개 동시 적용 → `seq == 64`), 같은 이벤트 재적용은 1건, 낮은 seq는 무시,
  `max-items` 초과는 잘림, 사용자끼리 섞이지 않음

## Unreleased — 순서에 관대한 병합 (#178)

### 변경

- **낮은 `seq`를 버리지 않고 제자리에 끼워 넣는다.** 병합이 "적용된 `seq` **집합**에서 큰 것
  `max-items`개"가 되어 **도착 순서가 값을 바꾸지 않는다.** 항목이 자기 `seq`를 갖는다
- **E2의 최대 원인이 사라졌다** — 같은 조건 재측정: `disorder` **0% → 100%**(순서를 일부러 뒤집어
  20명 전원을 갈라지게 했던 조건), `order-in-process` **83.3% → 100%**, `order-kafka` 100% 유지.
  **순서 보장(사용자별 큐)을 사지 않고 됐다** → [E2-c 리포트](personalization/docs/runs/20260921-e2c-순서관대-재측정/report.md) ·
  [ADR-035](docs/adr/ADR-035-order-tolerant-context-merge.md)
- **컨텍스트의 의미가 바뀐다**: "최근 **적용된** 것" → "최근 **일어난** 것". 지나간 시점의 항목이
  뒤늦게 끼어들 수 있고, `max-items` 초과 시 버리는 것도 "가장 먼저 적용된 것" → **"가장 낮은 `seq`"** 로
  바뀐다. 그래서 ADR을 썼다
- **키에 값 판을 붙였다**(`ctx:{userId}:v2`). 값 모양이 바뀌어 옛 키와 호환되지 않는다 —
  컨텍스트는 TTL 7일의 캐시라 마이그레이션하지 않고 새 키로 시작한다
- **기본 전달 방식은 `KAFKA` 유지.** 두 방식이 같은 값을 만들게 됐지만, `IN_PROCESS`를 기본으로 두면
  **아무도 소비하지 않는 토픽이 무한히 자란다**(발행은 유지되는데 컨슈머 그룹이 없다). 근거를 ADR-034에 갱신

### 검증

- `ContextStoreConcurrencyTest`에 **순서 무관성** 테스트 추가 — 같은 `seq` 집합을 뒤섞어 적용한 결과가
  순서대로 적용한 결과와 같아야 한다. 낮은 `seq`도 제자리에 들어가고, `max-items` 초과는 **가장 낮은
  `seq`부터** 버린다
- 대조 도구가 **키 형식을 가정하지 않는다** — 키에 판을 붙였을 때 옛 키를 봐서 세 조건이 전부
  "컨텍스트 없음"으로 나온 적이 있다(검사가 틀렸는데 시스템이 틀린 줄 알 뻔했다)

## Unreleased — 기본 전달 방식을 `IN_PROCESS`로 (M2 잔여 재측정)

### 변경

- **기본 전달 방식이 `KAFKA` → `IN_PROCESS`로 돌아왔다.** 새 구현(순서 관대 병합) 기준으로 다시 쟀다:
  `IN_PROCESS` e2e p95 **15ms** vs `KAFKA` **31ms**(브로커가 더한 p95 **+16ms**), 대기 0 반영률
  **99.7%** vs 8.3%. **정확성 축에서 셋이 같으므로**(order 조건 100%) 지연·운영 비용이 낮은 쪽을 고른다.
  E1의 사전 등록 규칙이 그대로 발동했다 — E2의 차단 사유(유실)가 #179·#180으로 없어졌기 때문이다
- **규칙을 하나 더 박았다**: *기본값은 **정확성 축이 모두 같을 때** 지연·운영 비용이 낮은 쪽으로 정한다.*
  정확성 축이 갈리면 다시 뒤집는다
- E2-c에서 "`IN_PROCESS`를 기본으로 두면 아무도 소비하지 않는 토픽이 자란다"고 적었는데
  **조건을 빠뜨린 것이었다** — 발행은 `kafka` 프로파일에서만 일어나므로 기본 설정에는 토픽이 없다.
  그 문제는 `IN_PROCESS` + `kafka` 프로파일 조합에서만 생긴다(문서에 남김)
- `late` 조건 재측정: `dropped` **70% → 5%**, 남은 5%는 진짜 소비 지연(`behind`)
- 이 기본값의 이력은 **네 번**이다(E1 → E2 → E2-b/c → E2-e). 매번 측정이 이유를 줬고,
  틀린 판단은 틀렸다고 적었다 — [ADR-034](docs/adr/ADR-034-personalization-context-deployment-unit.md) ·
  [E2-e 리포트](personalization/docs/runs/20260921-e2e-m2-잔여-재측정/report.md)

## Unreleased — 위시리스트(찜)

### 추가

- **위시리스트(찜)** — `wishlist` 모듈, `V58`, `GET`·`POST`·`DELETE /api/v1/wishlist`(멱등).
  상품 카드·상세·마이페이지에 하트. **서버 저장 + 로그인 필수**이고, 그 판단과 대가는
  [ADR-033](docs/adr/ADR-033-wishlist-server-side.md)에 있다
- **상품 카드 하트** — 홈·카테고리·검색·함께 본 상품에 한 번에 적용(`productCard` 한 곳)

### 변경

- 없는 상품을 찜하면 **404 `PRODUCT_NOT_FOUND`** — 카탈로그와 같은 코드를 쓴다
- 동시 요청이 유니크 제약에 부딪히면 **500이 아니라 409 `DUPLICATE_REQUEST`**

### 문서

- `docs/09-ERD-설계.md` §12.8(스키마), `docs/10-API-스펙.md` §10(API), ADR-033 추가
- README 수치 정정(커밋·ADR·이슈·PR), 잘못된 경로 4곳 수정
  (`ADR-006`, `docs/ROADMAP-TRADEOFFS.md`), 문서 번호 충돌 정정(#172 산출물 경로)

## 2026-09-19 — storefront-v1 (이커머스 스토어프론트 v1)

결제 데모 콘솔 하나뿐이던 정적 화면을 이커머스 쇼핑몰로 넓혔다. 모든 화면은 실제 API를 호출한다.

### 추가

- **카탈로그(읽기 전용)** — `GET /api/v1/categories`·`/products`·`/products/{id}`.
  비로그인 공개, 쓰기 표면 없음
- `categories` 테이블과 `products` 탐색 컬럼, 6카테고리·36상품 시드 (V53·V54)
- **스토어프론트 화면** — 홈 `/` · 카테고리 `category.html` · 검색 `search.html` ·
  상세 `product.html` · 장바구니 `cart.html` · 주문/결제 `checkout.html` ·
  주문내역 `orders.html` · 로그인 `login.html`
- 카드·포인트·월렛 복합결제, 202 UNKNOWN은 주문 조회로 폴링

### 변경

- 기존 결제 콘솔은 `console.html`로 이전(모듈 데모 보존), `admin.html`은 그대로
- 가격·재고의 권위는 주문·결제 경로에 그대로 남긴다 →
  [ADR-031](docs/adr/ADR-031-storefront-catalog-read-api.md)
- 저장소 워크플로우(이슈/PR 템플릿·CONTRIBUTING) 정비

### 검증

- `./gradlew test` 통과(1065+), 실기동에서 전 화면 200과 주문→승인 PAID/DONE 확인
