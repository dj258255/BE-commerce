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
