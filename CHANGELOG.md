# 변경 기록

릴리스별로 **사용자에게 무엇이 나갔는가**를 한 곳에 모은다. 설계 결정의 이유는 [ADR](docs/adr)에,
검증 기록은 `docs/performance/`·`personalization/docs/runs/`에 있다 — 이 파일은 그 둘을 대신하지
않고 "무엇이 언제 나갔는가"만 적는다.

> 이 파일은 2026-09-20에 만들었다. 그 이전 릴리스는 GitHub Releases에만 있고 여기로 옮기지 않았다
> (커밋 로그와 ADR이 그 시기의 기록이다). 여기서부터는 릴리스마다 아래에 한 절씩 더한다.

## Unreleased — 추천 A/B 실험을 돌릴 수 있다 (#256)

### 변경

- `app.experiments.rec-history.enabled=true` 면 사용자를 대조·실험군에 고정 배정한다(해시, 저장하지 않음). 실험군은 구매 이력 + 재구매 우선
- 홈 1쪽 응답과 노출 기록에 `experiment` · `variant` 가 실린다(V68)
- `tools/ab_analysis.py` 가 노출된 상품에 한해 클릭·구매를 변형에 귀속하고 SRM · 차이 구간을 낸다
- 기본은 꺼져 있다

### 왜

[ADR-061](docs/adr/ADR-061-ab-assignment-and-attribution.md) · [검증](personalization/docs/runs/20260924-ab-e2e/report.md)

## Unreleased — GenPage 가 구매 이력을 읽고, 이력을 실제로 받는다 (#254)

### 변경

- `app.recommendation.history-source=purchases` 면 추천 모델에 결제 완료 주문의 상품(최근 것부터)을 넣는다. 기본값은 `activity` 그대로
- `app.recommendation.repeat-first=true` 면 최근 산 것을 먼저 두고 빈칸을 모델로 채운다(구매 이력일 때만)
- **고친 결함**: 앱이 GenPage 모델 서버로 보낸 요청 본문이 서버에서 빈 것으로 읽혔다. 모델이 모든 사용자를 이력 없는 사용자로 생성하고 있었다
- 모델 서버는 `history` 가 없는 요청을 400 으로 거절한다

### 왜

[ADR-060](docs/adr/ADR-060-genpage-serves-purchases.md) · [리포트](personalization/docs/runs/hm-genpage-report.md)
## Unreleased — CDC 커넥터가 떠 있는지와 흐르는지를 감시한다 (#252)

### 변경

- `APP_CDC_HEALTH_CONNECT_URL` 을 주면(kafka 프로파일) `cdc_connector_running` · `cdc_heartbeat_age_seconds` · `cdc_connect_reachable` 를 낸다
- 알림 `CdcConnectorNotRunning` · `CdcHeartbeatStale` · `CdcConnectUnreachable`
- 두 커넥터에 하트비트(10초)를 켜고, 변환이 데이터 토픽에만 걸리도록 조건을 붙였다. 커넥터를 다시 등록해야 적용된다
- `poll.interval.ms=100` 은 그대로 둔다(500 대비 Connect CPU +1.0~2.5%p, 지연 p95 97ms 대 478ms)

### 왜

[ADR-059](docs/adr/ADR-059-cdc-poll-and-health.md) · [실측](docs/performance/cdc-cost-and-health.md)

## Unreleased — 조회가 몰리면 조회를 먼저 돌려보낸다 (#250)

### 변경

- 진행 중인 조회(`GET /api/v1/products**` · `/home**` · `/categories**`)가 16개면 새 조회는 503 + `Retry-After: 1` 을 받는다
- 웹훅·결제·주문은 돌려보내지 않는다. 조회가 몰려도 웹훅이 토스 10초 규약 안에 답한다(PG 5초 · 조회 200/s 에서 15.6% → 0%)
- 끄려면 `APP_WEB_BROWSE_SHED_ENABLED=false`

### 왜

[ADR-058](docs/adr/ADR-058-shed-browse-before-webhook.md) · [실측](docs/performance/webhook-priority.md)

## Unreleased — 미확정 복구가 확정 못 한 건에 막히지 않는다 (#248)

### 변경

- PG 가 "진행 중"이라고 답하거나 조회가 실패한 미확정 결제는 다음 시도를 1·2·4·8분 뒤(상한 10분)로 미룬다(`app.recovery.policy=backoff`, 기본값)
- 그런 건이 청크만큼 쌓여도 뒤의 미확정이 계속 풀린다. 전에는 5분 동안 한 건도 못 푼 경우를 재현했다
- `payments` 에 `recovery_attempts`·`recovery_next_at` 컬럼을 더했다(V67)

### 왜

[ADR-057](docs/adr/ADR-057-recovery-backoff-over-order.md) · [실측](docs/performance/recovery-order.md)
## Unreleased — 상품 변경이 검색에 1초 남짓 만에 반영된다 (#246)

### 변경

- 상품·가격·재고 변경을 CDC(`catalog-cdc` 커넥터 → `catalog.change`)로 받아 검색 색인에 반영한다. 반영 지연 p95 1.1초(전에는 최대 10분)
- 품절된 상품이 "재고 있음" 검색 결과에서 1초 남짓 만에 빠진다
- kafka 프로파일과 커넥터가 있을 때만 동작한다. 없으면 전처럼 10분마다 다시 만든다

### 왜

[ADR-056](docs/adr/ADR-056-search-index-freshness-by-cdc.md) · [실측](docs/performance/search-freshness.md)

## Unreleased — 검색어와 필터·패싯을 함께 건다 (#244)

### 변경

- 검색어가 있을 때도 `category`·`colour`·`productType`·`minPrice`·`maxPrice`·`inStock` 필터가 적용된다. 전에는 무시됐다
- `/api/v1/products/facets` 가 `q` 를 받는다. 검색 결과의 색상·종류 개수가 나온다
- `/api/v1/products` 에 `inStock` 필터를 더했다
- 필터·패싯은 검색 엔진 안에서 센다. 엔진이 실패하면 상위 500개를 DB 에서 거르는 쪽으로 물러선다(개수가 모자랄 수 있다)

### 왜

[ADR-055](docs/adr/ADR-055-search-filters-in-engine-and-scale-limit.md) · [실측](docs/performance/search-filters-scale.md)

## Unreleased — GenPage 를 검증 기간으로 다시 튜닝했다 (#242)

### 변경

- 학습 스크립트에 검증 모드(`GENPAGE_MODE=validate`)와 최종 모드(`final`)를 넣었다. 검증 주로 고르고 홀드아웃은 한 번만 본다
- 저장된 GenPage 모델을 최종 설정(차원 64 · 6에폭)으로 바꿨다. **기본값은 여전히 꺼져 있다** — 홀드아웃 0.020930 으로 선(0.023354)을 못 넘었다

### 왜

[ADR-054](docs/adr/ADR-054-genpage-validation-tuning.md) · [리포트](personalization/docs/runs/hm-genpage-report.md)

## Unreleased — 작은 GenPage 모델을 붙인다(기본값 꺼짐) (#238)

### 변경

- `app.recommendation.model.kind=genpage` 로 켜면 추천 행과 홈 다음 쪽 행을 **모델이 생성한다**. 기본값은 `stub` 그대로다
- 모델 서버(`personalization/serving/genpage_server.py`)가 느리거나 죽으면 추천은 인기로, 홈 다음 쪽은 규칙 행으로 물러선다
- 모델 행은 `GENPAGE` 로 표시한다. 품절은 앱이 생성 뒤에 거른다

### 왜 켜지 않았나

MAP@12 0.020937 로 측정 전에 정한 선(0.023354, 마지막 구매 재추천)을 못 넘었다. ALS 의 2.8배다.
[ADR-053](docs/adr/ADR-053-genpage-mini-not-default.md) · [실측](personalization/docs/runs/hm-genpage-report.md)

## Unreleased — 홈을 여러 쪽으로 나눠 만든다 (#237)

### 변경

- 홈 응답에 `page`·`nextCursor` 가 붙는다. `?cursor=` 로 다음 쪽을 받으면 **대분류별 인기 행**이 나온다
- 다음 쪽은 앞 쪽에서 보여 준 상품을 다시 내지 않는다(커서가 들고 다닌다)
- 1쪽을 본 뒤에 본 상품의 대분류가 다음 쪽 첫 행이 된다(`CATEGORY_POPULAR_SESSION`)
- 인기 표에 거의 없는 대분류(아동복)는 그 대분류의 신상품으로 채운다

### 왜

[ADR-052](docs/adr/ADR-052-home-pagination-cursor.md) · [실측](docs/performance/home-pagination.md)

## Unreleased — 상품 검색을 앱 안 Lucene 으로 바꾼다 (#236)

### 변경

- **검색어 결과가 관련도순이다.** 전에는 상품명·브랜드에 부분 일치를 걸고 신상품순으로 냈다. 이제 상품명·종류·설명을
  보고, 어형 변화와 오타(3~5자 1글자, 6자 이상 2글자)를 허용한다. 오타·어형 쿼리 nDCG@10 0.071 → 0.808(실측)
- **정렬을 고르면 검색 결과 상위 500개 안에서 정렬한다**(가격순 등). 정렬을 고르지 않으면 관련도순이다
- 엔진이 실패하면 필드를 넓힌 DB 검색으로 물러서고 `catalog.search.fallback` 이 오른다
- 새 상품이 검색에 보이기까지 **최대 10분**이 걸린다(색인을 10분마다 다시 만든다). 가격·재고는 즉시다

### 왜

[ADR-051](docs/adr/ADR-051-search-engine-by-quality.md) · [실측](docs/performance/search-quality.md).
ES·OpenSearch 도 쟀고 품질이 같아 운영 비용으로 뺐다.

## Unreleased — 머지 방식을 머지 커밋으로 통일한다

### 변경

- **PR 은 머지 커밋으로 머지한다.** 저장소 설정에서 **스쿼시·리베이스를 껐다** — 규칙만 적어 두면
  또 갈린다(실제로 갈렸다: 최근 200커밋 안에 스쿼시 63건 · 머지 커밋 40건이 섞여 있었다)
- 머지 커밋 제목이 **PR 제목을 그대로** 쓰도록 맞췄다. `gh pr merge --merge` 만 하면 되고
  `--subject` 를 따로 줄 필요가 없다
- `CONTRIBUTING.md` 의 "스쿼시 머지" 규칙을 고쳤다 — **문서가 실제와 반대로 적혀 있었다**

### 왜

스쿼시는 브랜치의 커밋 메시지를 PR 제목 한 줄로 눌러 버린다. 이 저장소는 커밋마다 "왜" 를 적어 왔고,
그게 사라지면 **결정의 기록이 PR 본문에만 남는다.**

### 남는 것

- **과거는 안 고친다.** 2026-09-23 이전 PR 63건은 스쿼시로 들어가 있고, 그걸 바꾸려면 `main` 이력을
  다시 써야 한다. 이 저장소는 이미 한 번 이력을 다시 썼고 그 흔적이 `backup/` 브랜치로 남아 있다
- 그래서 **`git branch --merged` 는 그 시기 브랜치에 여전히 거짓 음성**을 낸다. 판단은 PR 상태로 한다

## Unreleased — 저장소를 영역으로 나눈다 (#142 · #144 / M9)

### 변경

- **Java 앱이 `commerce/` 로 들어갔다.** Gradle 프로젝트 루트가 거기이고 wrapper 는 루트에 그대로다 —
  `./gradlew -p commerce test` 로 돈다. `consumer-app` 이 이미 `-p` 로 돌고 있던 것과 같은 방식이다
- **`consumer-app` 은 `commerce/consumer-app` 으로** 따라갔다. 결제 DLT 를 읽는 것이라 commerce 를
  떼면 같이 간다
- **`docs/`·`monitoring/`·`k6/`·`tools/`·`cdc/` 는 루트에 남겼다.** 둘 다 쓰기 때문이다 —
  `alert-rules.yml` 한 파일에 분쟁 마감 알림과 개인화 반영 지연 알림이 같이 있다
- **`ops/` 로 묶지 않았다.** 소유자가 한쪽이 아니라 둘 다여서, 한 겹 더 감싸도 경계는 안 선명해지고
  링크만 깊어진다
- **깨진 링크 검사를 CI 에 넣었다**(`tools/check_doc_links.py`)

### 왜 테스트가 경로를 직접 안 들고 있게 했나

작업 디렉터리가 `commerce/` 로 바뀌어 `Path.of("docs/...")` 가 안 풀린다. `"../docs/..."` 로
깊이를 박는 대신 **표식을 찾아 올라가는 `RepoRoot`** 를 뒀다 — 다음에 한 번 더 옮겨도 안 깨진다.
못 찾으면 예외를 던진다. **빈 파일을 읽고 "검사할 것이 없어 통과" 하는 것이 가장 나쁜 실패**여서다.

### 검증

- `./gradlew -p commerce clean test` — **1,178건 통과**(실패 0 · 건너뜀 2, 둘 다 `ci/allowed-skips.txt` 등재)
- `./gradlew -p commerce integrationTest` 통과 · `./gradlew -p commerce/consumer-app build` 통과
- **두 이미지를 실제로 굽고 jar 가 들어갔는지 확인했다** — `docker build` 는 jar 경로가 틀려도
  성공할 수 있어서, 이미지 안의 `/app/app.jar` 크기를 직접 봤다(108MB · 33MB)
- `promtool check rules` 24건 · 억제 프로브 6건 → 4건
- **깨진 링크 11건을 찾아 0건으로 만들었다.** 그중 8건은 이번 이동과 무관하게 **원래 깨져 있던 것**이다

### 안 고친 것

- **과거 실측 기록(`**/runs/`)의 명령과 로그는 그대로 뒀다.** 그때 실제로 친 명령이고 실제로 찍힌
  로그다. 지금 경로로 고쳐 쓰면 없던 일을 있었던 것처럼 만드는 것이다
  (일괄 치환이 한 번 건드려서 바이트 단위로 되돌렸다)
- CHANGELOG 의 과거 절에 있는 `./gradlew test` 표기도 같은 이유로 그대로다

## Unreleased — 추천 모델을 학습해 보고 넣지 않았다 (#217 / M12)

### 변경

- **나간 기능이 없다.** `StubModelClient` 가 그대로다. `ModelClient` 계약도 한 글자 안 바뀌었다
- 바뀐 것은 **근거**다 — "모델 품질은 목표가 아니다" 가 추측이 아니라 **재고 내린 결론**이 됐다

### 검증 — 선을 먼저 정하고 쟀다

| | MAP@12 | 평가 고객 |
|---|---|---|
| **`repeat_last` (넘어야 할 선)** | **0.023354** | 68,984 |
| `popular_recent7d` | 0.008748 | 68,984 |
| **ALS 가장 높은 구성** | **0.007608** | 68,984 |

**못 넘었다. 선의 3분의 1이고 인기보다도 낮다.**

한 번 재고 끝내지 않았다. 처음 구성은 `implicit` 의 기본값 `alpha=1.0` 을 그대로 썼는데, 그러면
신뢰도가 `1 + alpha·r` 이라 **가중이 사실상 없다**(원 논문 값은 40). **안 열어 본 축을 안 된다고
적을 수 없어** 세 구성을 더 쟀다 — alpha 1·10·40, factors 64·128.

- alpha 는 **안쪽에 최적점**이 있다(10 에서 가장 높고 40 에서 내려간다)
- factors 를 두 배로 올리면 **학습 시간만 2.5배**가 되고 점수는 같은 밴드다
- 네 구성이 **0.0067~0.0076** 에 모여 있다. 두 축을 움직여 얻은 폭이 15% 미만인데 선까지는 207% 가 필요하다

같은 명령을 두 번 돌려 같은 값이 나왔다(`random_state=42`).

### 아직 아닌 것

- **`AlsModelClient` 를 만들지 않았고 지연·메모리도 안 쟀다.** 넣지 않기로 한 것의 서빙 비용을
  재는 것은 일이 아니다
- **"ALS 계열이 안 된다" 가 아니다.** 네 구성이 졌다. `regularization`·`iterations` 는 고정했고
  BPR·item2item·시퀀스 모델은 재지 않았다
- **콜드스타트 5,572명**(홀드아웃의 8.1%)은 ALS 가 점수를 못 낸다. 기준선도 같은 조건이다
- **오프라인 지표는 게이트고 최종 판정은 온라인 A/B 다.** 실사용자가 없으므로 그 경계는 남는다

## Unreleased — CDC 가 아웃박스와 같은 신선도를 낸다 (#209 B5 / M12)

### 변경

- **전달 방식에 `CDC` 를 더했다** — `transport=CDC` 면 앱이 발행하지 않는다. 켜 두면 활동 한 건이
  **두 번 흐른다**(저장은 binlog, 발행은 아웃박스). 컨슈머는 멱등이라 안 깨지지만 **측정이
  "둘 중 빠른 쪽" 을 재게 된다**
- **CDC 모드에서도 컨슈머가 뜬다** — 게이트가 `KAFKA` 만 열려 있어 **컨슈머 빈이 아예 없었다.**
  커넥터는 정상이고 토픽에 쌓이는데 읽는 쪽이 없어 **반영률 0%** 가 나왔다(오류 로그 0건)

### 검증 — 첫 결론을 뒤집었다

| 전달 | 반영률 | **lag p95** |
|---|---|---|
| 아웃박스 | 100.0% | **14ms** |
| CDC (기본값) | 19.5% | **1,944ms** |
| **CDC (튜닝)** | **98.5%** | **13ms** |

기본값만 보면 **"CDC 가 139배 느리다"** 다. 느렸던 것은 CDC 가 아니라 **명시하지 않은 기본값**이었다
(`poll.interval.ms` 500 → 100 외 둘). **튜닝한 CDC 는 아웃박스와 사실상 같다.**

그래서 **CDC 의 값은 신선도가 아니라 결합을 끊은 것**이다 — 신선도는 같은 값에 그 성질을 얻었다.

### 아직 아닌 것

- `poll.interval.ms` 를 줄인 **비용을 안 쟀다**(DB·커넥터 부하)
- **커넥터가 죽는 것을 감시하는 수단이 없다**(#215). 이 수치는 커넥터가 살아 있을 때의 값이다
- `waitMs` 는 0·100 만 봤다. **합성 활동이고 단일 장비다**

## Unreleased — 활동 로그를 CDC 로 나른다 (#209 / M12)

### 변경

- **활동 로그가 binlog 를 타고 개인화에 간다** — `user_activities` 의 변경을 Debezium 이 읽어
  `user.activity` 토픽으로 보낸다. **컨슈머는 손대지 않았다**(`KafkaContextTransport` 가 이미 그
  토픽을 듣고 있다). 앱이 직접 발행하면 **쓰기 경로에 브로커 의존이 붙어** 브로커가 흔들릴 때
  활동 기록이 주문·결제 경로를 건드린다 — 그것을 끊었다
- **메시지 키를 `userId` 로 고정했다** — Debezium 기본 키는 테이블 PK(`id`) 인데, 컨슈머는
  *같은 사용자의 활동이 같은 파티션에 온다*는 성질 위에서 **분산 락 없이** read-modify-write 한다.
  키가 `id` 면 그 전제가 **조용히 깨진다**(지금 파티션이 1개라 안 드러날 뿐이다)
- **개인화 유저 매핑 표**(`V66`) — H&M 고객 137만을 커머스 회원으로 만들지 않고 필요한 만큼만 잇는다.
  매핑이 없으면 **인기 폴백**을 탄다(콜드스타트와 같은 경로, 별도 분기 없음)
- **`docs/03` 의 아웃박스 결정은 그대로다** — 두 CDC 가 다른 문제를 푼다. 아웃박스는 발행 보장,
  활동 로그는 결합 차단. 가른 이유와 대가는 [ADR-047](docs/adr/ADR-047-cdc-for-activity-not-outbox.md)

### 검증

- `INSERT` 1건 → 토픽에 키 `999003`, `occurredAt` ISO 문자열로 도착(실제 기동)
- 그 페이로드 **문자열 그대로**를 `KafkaContextTransportTest` 에 넣어, 이벤트에 없는 `id`·`created_at`
  이 섞여도 역직렬화가 안 깨지는 것을 회귀로 고정(4/4)
- **띄워 보고 고친 것 다섯** — 그중 `debezium/connect` 2.7 계열은 MySQL 8.4 에서 `SHOW MASTER STATUS`
  를 불러 죽는데 **커넥터 상태는 `RUNNING` 으로 보인다**. 상태만 보면 정상으로 읽힌다(3.0.0.Final 로 해소)

### 아직 아닌 것

- **신선도가 좋아졌다고 쓰지 않는다.** E1 재측정 전이다
- `snapshot.mode=schema_only` 라 **기존 49,010 행은 흐르지 않는다**
- CDC 와 아웃박스가 **둘 다 살아 있어** 활동이 두 번 흐른다. 측정 전에 무엇을 끌지 정해야 한다(#211)

## Unreleased — 홈 컴포저가 스토어프론트에 붙는다 (#130 / M7)

### 변경

- **정적 스토어프론트 홈이 홈 컴포저 API를 소비한다** — 로그인한 사용자에게 "너를 위한 추천" 섹션이
  뜨고, **행마다 따로** 그린다(행 구성이 이 API의 산출물이라 하나로 뭉치면 그게 안 보인다).
  로그인하지 않았거나 조립에 실패하면 **섹션을 조용히 지우지 않고 그 사실을 적는다** —
  *개인화가 죽어도 상점은 열려야 한다*
- **홈 아이템에 `imageUrl`·`inStock` 을 더했다** — 소비자가 카드를 그리려면 그 값이 필요하고, 없으면
  **상품 API를 한 번 더 불러야 해서 조립한 것과 화면이 갈라진다**. 조립이 이미 카탈로그 카드로 그 값을
  쥐고 있으므로 함께 내보낸다(목 계약 대비 추가 — fixture `_note` 에 적었다)
- **실연동 검증(브라우저, 실제 기동)**: 로그인(데모 1) 후 홈 → `모델 · 행 3 · 항목 16 · 조립 93ms ·
  중복 5 제외`, 3개 행(최근/추천/인기)에 실제 H&M 상품, 개발자 로그에 `GET /personalization/homepage`
  응답 전체가 남는다

## Unreleased — 합성 리뷰 (#168)

### 추가

- **리뷰**(`product_reviews`, `V64`) — 상세 화면에 리뷰를 붙였다. **합성임을 세 곳에서 밝힌다**:
  DB(`source`), API(`reviewsSynthetic`), 화면(합성 배지 + "데모용으로 만든 것" 문구)
- `ProductDetailView.ReviewView` · `ProductReviewRepository`(목록 조회만) ·
  `seed_synthetic_reviews.py`(결정적 생성 — 씨앗은 상품 id) · 상세 화면 리뷰 블록 + CSS
- **`ReviewSyntheticGuardTest`** — 상품·카드 record 에 **집계성 필드가 생기면 깨진다**.
  "만들지 않기로 했다"를 문서가 아니라 **구조로** 막는다
- `CatalogApiIntegrationTest` — 공개 읽기 표면(로그인 없이 열려 있는 가장 넓은 문)에 통합 테스트가
  **없었다**. 리뷰 계약(`reviewsSynthetic`)과 404 를 여기서 고정한다

### 변경

- **평점을 집계하지 않는다** — 평균·개수를 `products` 에 두지도, API 에 내보내지도 않는다.
  **평점 정렬·필터도 만들지 않는다.** 합성 리뷰 5건의 평균 4.3은 **아무것도 측정하지 않은 숫자**인데,
  화면에 "4.3점"으로 뜨면 상품의 품질 신호가 되고 정렬·추천으로 흘러간다(ADR-046)
- **리뷰는 인기 두 창의 합집합(~380개 상품, 1,322건)에만 붙인다** — 홈의 인기 행이 `recent_7d` 를
  쓰므로 전체 기간 상위만 고르면 **홈에 뜨는 상품에 리뷰가 없었다**(실제로 그렇게 만들어 1위 상품이
  리뷰 0건이었고, 합집합으로 고쳤다)
- **파이프라인 적재의 문자셋 버그를 찾아 고쳤다** — `docker exec mysql` 에 `--default-character-set=utf8mb4`
  가 없어 한글이 **이중 인코딩**됐다(`잘` → `ìž˜`). 네 스크립트 전부 같은 경로였다
  (`promote_products`·`attach_images`·`export_popular`·`seed_synthetic_reviews`).
  `products.description` 이 영어라 지금까지 드러나지 않았다 — **리뷰 적재가 드러냈다**
- **알려진 대가**: 리뷰 문구가 일반적이라 읽으면 합성인 것이 티가 난다(구체적 후기를 지어내면 데이터에
  없는 사실을 만드는 것이므로 **의도한 대가**다). 커버리지도 인기 상품에만 있다

## Unreleased — 풀 크기와 창 (#187 / E4c)

### 추가

- **하네스에 두 축**: `ITEM_POOL`(EXPERIMENT 24개 / CATALOG 105,545개) · `MODEL_LATENCY_MS`
- E4c 리포트(`personalization/docs/runs/20260921-e4c-풀크기와-창/report.md`) + 원자료 3벌 ·
  `constraint_report.py` 에 후보 집합·모델 지연 열

### 변경

- **E4b 의 "두 정책의 확인 비용이 같다"는 합성 풀(24개)에서만 참이었다** — 실제 카탈로그에서
  `AT_GENERATION_START` 는 **2,859ms**, `AFTER_GENERATION` 은 **3ms**(**953배**). 원인은 **읽는 양**이다
  (스냅샷 = 풀 전체, 출력 확인 = 결과 12개). `AT_GENERATION_START` 는 **실카탈로그 기본 후보에서 제외**
- **"창의 크기가 곧 위반율"이 반증됐다** — 창을 **6.6배**(63 → 413ms) 늘려도 위반율은
  **62.08% → 62.50%**(1.007배). ADR-038 의 그 설명을 **틀린 것으로 고쳤다**
- **대신 요청률이 위반율을 바꾼다**: 같은 정책·지연에서 **30/s 29.63% vs 8/s 62.08%**(둘 다 coverage 100%)
  — 후보 설명은 *요청당 변화 이벤트 수*이고 다음 실험으로 남긴다(두 점뿐이라 결론이 아니다)
- **위반율의 절대값은 풀 크기의 함수였다**(E4c 가 계산으로 확인) — 변화가 **화면 항목을 건드릴 확률**이
  `화면 항목 ÷ 풀 크기`다. 합성 풀(24 → 50%)에서 62%, 실제 카탈로그(105,545 → 0.011%)에서 기대 0.26% ·
  관측 0건. **E4·E4b 의 위반율 절대값(29.63% 등)을 "실제 위반율"로 인용하면 안 된다** —
  **정책 간 순서는 유지**되고 기본값도 바뀌지 않지만 **근거의 크기**가 바뀐다
- **`AT_RESPONSE` 는 기본 후보가 아님**을 ADR-038 에 못 박았다(제거하지는 않는다 — 재실험할 값을 남긴다)
- **구매 단계**: 빈도는 **재지 못했다**(결제 흐름을 태우는 시뮬레이션이 필요). 대신 메커니즘을 확인했다 —
  **주문 생성은 재고를 보지 않으므로** 낡은 가용성은 거절이 아니라 **망취소 보상**으로 나타난다

## Unreleased — 진짜 인기 신호와 되채우기 (#198①② / M7 후속)

### 추가

- **인기 통계 표**(`product_popularity`, `V63`) — `export_popular.py` 가 거래 **31,788,324행**을 세어
  상위 200개를 창 2개(`recent_7d`·`all_time`)로 내보낸다. `computed_at` 에 **데이터 기준일**을 적는다
  ("최근"이 벽시계가 아니라 데이터 기준이라는 사실을 값이 스스로 밝히게)
- **되채우기**(`app.home.refill-depth`) — 규칙이 버린 칸을 **더 깊은 후보**로 채운다
- 하네스: `tools/home_ab_report.py`(A/B 를 **여러 런으로** 내고 폭을 함께 낸다) ·
  `run-home-composition.sh` 에 `POPULARITY`·`REFILL_DEPTH` 축 · `home_report.py` 에 **행별 평균 표시 순위**
- 홈 아이템에 `category`·`rank` 를 함께 낸다 — 조립이 **그 값으로 판단했으므로** 응답이 그 결정을 밝힌다

### 변경

- **`popular` 행이 진짜 인기가 됐다** — 후보 집합에 퍼뜨리던 것을 M1 인기 통계로 바꿨다. 퍼뜨리기는
  폴백으로 남기되 **조용하지 않다**(`recommendation.popular.signal.fallback` 지표 + 경고 로그)
- **되채우기 기본값 = 2** — 사전 등록 기준 셋으로 판정: 항목 **+12.6%**(≥10% 통과) · 평균 표시 순위
  **1.18배**(<2배 통과) · 지연은 **폭이 차이보다 커서 판정 불가**
- **알려진 대가**: 진짜 인기로 바꾸면 화면이 **−18.4%**(17.8 → 14.5개) 짧아진다 — 되채우기가 그중
  +12.6%를 되산다. **더 좋은 신호가 더 나쁜 화면을 만든다**
- **행마다 대우가 다르다**: 인기·최근 행은 되채울 수 있고 **모델 행은 못 한다**(모델에 더 요구하면
  `result-size` 가 커져 과부하 게이트의 **입장 판단**까지 바뀐다 — E5 영역). 측정된 한계다

## Unreleased — 노출 기록 보존 정책 (#198③ / M7 후속)

### 추가

- **`ImpressionCleanupScheduler`** — 보존 기간(기본 7일)이 지난 노출 기록을 **배치(5,000행)로** 지운다.
  260만 행을 한 트랜잭션에 지우면 긴 잠금이 생겨 홈 조회를 막으므로, 배치 반복에 회차 상한(20)도 둔다
- `V62` — 자르는 술어용 인덱스(`created_at`). V61의 `(user_id, created_at)` 은 `created_at` 범위
  스캔에 쓸 수 없어, 정리 한 번이 표 전체를 훑게 된다
- 설정 `app.home.impression-log.cleanup.*`(`retention-days` 7 · `batch-size` 5000 · `max-batches-per-run` 20)

### 변경

- **ADR-043의 「알려진 한계: 보존 정책이 없다」를 닫았다** — 하루 260만 행이 쌓일 수 있던 결함이다
- **정리 게이트의 기본값이 다른 장치와 반대다(`true`)** — 표를 자라게 하는 쪽(기록)이 기본 on이므로
  고치는 쪽을 off로 두면 문서에 적어 둔 결함이 그대로 살아 있다. 부작용은 만료 행만 지우는 것으로 막았다
- **보존 일수의 근거는 측정이 아니라 관례다**(아웃박스와 같은 7일) — 이 표를 실제로 쓰는 사람이 없어
  "며칠이 맞는가"를 잴 대상이 없다. 쓰임이 생기면 그때 재서 정한다

## Unreleased — 검색 베이스라인과 도입 판정 (#172)

### 추가

- **검색 베이스라인 측정** — 고정 코퍼스(105,542행) · 고정 쿼리(필터·정렬·깊은 페이지·검색어·패싯) ·
  동시성 1/10/50. 하네스: `k6/storefront-search.js` · `tools/run-search-baseline.sh` ·
  `tools/search_baseline_report.py`, 원자료 `docs/performance/runs/20260921-검색-베이스라인/`
- [검색 엔진 비교](docs/31-검색-엔진-비교.md) · [ADR-044](docs/adr/ADR-044-no-search-engine-yet.md)

### 변경

- **검색 엔진을 지금 도입하지 않는다** — 사전 등록 기준 ②(**패싯 몫 82~90%**, 동시성 1에서도 82.5%)가
  발동했고, 이슈가 적은 조치 중 **싼 쪽(패싯 사전 집계)** 부터 한다. 기준 ①은 **발동하지 않았다**:
  동시성 50에서 목록 p95가 587ms지만 **필터 없는 목록도 587ms** 라 원인이 집계가 아니라 **포화**다 —
  기준이 물은 것은 "300ms를 넘었는가"가 아니라 "**넘은 이유가 집계인가**"였다
- **엔진 쪽 수치는 하나도 없다**(설치하지 않았다). 이 결정은 "지금 안 한다"이지 "엔진이 불필요하다"가 아니다

### 추가 (2단계 — 사전 집계)

- **패싯 사전 집계**(`FacetCache`, `app.catalog.facets.cache-ttl` 기본 60s · 상한 200개 ·
  지표 `catalog.facets.cache{result=hit|miss|evicted}`) — 붙이고 **같은 하네스로 껐다 켰다**:
  패싯 p95 **90.9 → 5.7ms**(동시성 1, **15.9배**) · 몫 **82.5% → 24.6%** → **기준 ② 해소**,
  **엔진 도입 조건이 사라졌다**(다른 쿼리는 1.0배 — 개선이 패싯에서만 왔다)
- **무효화는 TTL이다**: 카탈로그가 앱 밖에서 적재되므로(`promote_products.py`) 앱이 그 사건을 볼 수
  없다 → **TTL 창(60초) 안에서 낡은 개수를 보여준다**는 대가를 ADR-044 에 적었다
- **동시성 50은 47.2%로 근소**하다 — "해소"라고 적되 여유가 크지 않다는 사실을 함께 적었다

## Unreleased — 홈 노출 기록 (#130 / M7)

### 추가

- **홈 노출 기록**(`home_impressions`, `V61`) — 그 응답이 **무엇을 보여줬는가**를 **요청당 한 행**으로
  남긴다(노출 id 를 **순서 그대로**, 조립이 버린 것도 함께). 홈 한 번에 항목이 20개 나가므로 항목당
  행이면 30req/s 에서 **초당 600행**이다 — 요청당 한 행이면 30행이다
- `app.home.impression-log.enabled`(기본 on) · 지표 `home.impression.logged` / `.failed`

### 변경

- **기록 실패가 홈을 죽이지 않는다** — 홈은 제품이고 기록은 관측이다. 대신 **삼키지 않고** 경고와
  지표로 남긴다("기록이 없다"와 "기록이 실패했다"는 다르다)
- **Outbox/Kafka 에 태우지 않는다** — 노출은 도메인 상태가 아니라 관측이고, 이 저장소는 Outbox 비대화로
  이미 고생했다(부하 6회에 15만 행). 판단과 대가는 [ADR-043](docs/adr/ADR-043-home-impression-log.md)
- **알려진 한계**: 보존 정책이 없다 — 30req/s 로 계속 돌리면 하루 260만 행이다. 지금은 실험 중에만
  켜 두는 것이 맞고, 오래 켜 둘 때는 아웃박스·멱등키와 같은 정리 장치를 붙여야 한다(ADR-043)

## Unreleased — 홈 컴포저 (#130 / M7)

### 추가

- **홈 컴포저 모듈**(`home`) — 여러 출처를 **한 화면으로 조립**한다. 추천 코어는 별도 경계로 두고
  `RecommendationFacts` 계약으로만 소비한다(ADR-042). `GET /api/v1/personalization/homepage`
- **조립 규칙 3수준**(`app.home.rules`) — `NONE`(기준선) · `DEDUP`(중복+품절) · **`FULL`(기본, +카테고리
  다양성)**. 규칙을 동시에 만족시킬 수 없으므로 무엇을 우선할지 고르고, 각 수준이 무엇을 잃는지를
  **응답의 `stats` 가 밝힌다**(중복·품절·**다양성 상한**·미매칭)
- **추천 후보 집합의 원천**(`app.recommendation.item-pool`) — `CATALOG`(기본, 실제 상품) ·
  `EXPERIMENT`(합성 풀). **E4·E5 하네스가 EXPERIMENT를 명시**해 격리를 지킨다(ADR-042)
- `ProductCatalogFacts` 에 `categoryCode`·`allProductIds` — 홈이 상품 카드를 그리고 대분류를 보게
  (ADR-018의 "필요한 쪽이 생기면 그때 더한다")
- **M7 리포트** — `personalization/docs/runs/20260921-m7-홈-조립/`(8절 + 원자료).
  하네스: `tools/run-home-composition.sh` · `tools/home_report.py`

### 변경

- **조립 규칙은 지연을 쓰지 않고 노출을 쓴다** — 규칙 수준과 무관하게 추론 53ms·제약 7ms·잔차 25ms다.
  바뀌는 것은 **화면에 담기는 칸**(32 → 20 → 19)이다. 중복 제거가 가장 큰 일을 한다(32칸 중 **12칸**)
- **`ConstraintChecker.filterNow` 가 후보 풀 전체를 읽던 것을 고쳤다** — 합성 풀(24개)에서 3~4ms이던
  확인이 실제 카탈로그(105,545개)에서 **405ms** 가 됐다. 출력만 걸러내면 되는데 풀 전체를 읽고 있었다.
  **405ms → 7ms**. E4b의 "확인 1회 ≈ 3~4ms"는 **풀 24개에서만 유효**하다(리포트에 범위를 적었다)
- 홈은 **모델이 죽어도 200** 이다 — 폴백은 카탈로그만으로 조립하고 `source=FALLBACK` 으로 밝힌다

## Unreleased — 캐시 압축 임계값 (#129 / M6)

### 추가

- **캐시 값 코덱과 임계값**(`app.personalization.cache.codec` · `compress-threshold-bytes`) —
  `NONE`(기본) · `LZ4` · `SNAPPY`. 임계값 이상일 때만 압축하고, 압축한 값에는 **표식**을 붙인다
  (길이로는 압축 여부를 알 수 없다)
- **E6 실험 계기** — `POST /api/v1/experiments/cache/bench`(기본 off). 값 크기 × 코덱으로
  넣고 읽어 구간별 지연·저장량·CPU 를 돌려준다
- **E6 리포트** — `personalization/docs/runs/20260921-e6-캐시-압축-임계값/`(8절 + 원자료).
  하네스: `tools/run-cache-compression.sh` · `tools/cache_compression_report.py`. ADR-041
- 압축 라이브러리 `lz4-java` · `snappy-java`(Kafka 클라이언트가 이미 전이로 가져오던 것이라 직접 선언으로 고정)

### 변경

- **임계값은 1KB, 코덱은 `SNAPPY`** — 교차점은 **187B(손해, 저장량 1.070)와 279B(이득, 0.817) 사이**로
  관측됐다. 경계 근처의 이득은 몇 %라 값의 성질에 기대므로 **1KB**(66% 감소)로 잡는다.
  500KB 에서는 저장량 **83% 감소**에 **get p99 2.02ms → 0.27ms(7.5배)** 가 더해진다
- **코덱 선택이 교과서 순서를 뒤집었다** — 저장량은 LZ4 와 비슷한데 **CPU·지연은 SNAPPY 가 낮았다**
  (500KB 에서 242ms vs 359ms). **원인은 확인하지 못했고**(추정은 근거로 쓰지 않는다) 실측을 따랐다
- **압축은 아직 켜지 않는다**(기본 `NONE`) — 컨텍스트 캐시는 **Lua 가 값을 읽어 병합**하므로
  (ADR-035) 압축을 넣을 수 없다. 첫 적용 자리는 M7 의 홈 컴포저 결과 캐시다. **근거는 생겼지만
  적용할 자리가 없다** — 켤 때 쓸 값은 `codec=SNAPPY` · `compress-threshold-bytes=1024`

## Unreleased — 생성 범위와 계산 예산 (#128 / M5)

### 추가

- **생성 범위 정책 3종**(`app.recommendation.generation.scope`) — `RANKING`(기본) ·
  `PREFIX_AR_TOP_K` · `FULL_AR`. autoregressive 생성은 한 요청 안에서 **직렬**이라 범위가
  **모델 지연을 정한다** — 기준 지연 + 직렬 항목 수 × 항목당 시간
- **추천 응답의 예산 구간** — `contextMs`(활동 읽기) · `modelMs`(추론) · `checkMs`(제약 확인) ·
  `generationScope`. 전체 예산에서 **모델 몫**을 계산할 수 있게 하려는 것이다
- **E5 실험 리포트** — `personalization/docs/runs/20260921-e5-생성-범위-예산/`(8절 + 원자료).
  하네스: `k6/generation-budget.js` · `tools/run-generation-budget.sh` · `tools/generation_report.py`.
  ADR-040

### 변경

- **생성 범위가 과부하 정책의 대기 예상에도 쓰인다** — 범위가 지연을 바꾸므로 `OverloadGate` 가
  스텁과 **같은 순수 함수**로 지연을 낸다. 둘이 다른 값을 쓰면 정책이 잘못된 지연으로 판단한다
- **기본값은 `RANKING`** — E5 는 범위의 **비용만** 쟀다(용량 80 → 36 → 17/s, 도착률 고정 시 대가는
  coverage 로 94.4% → 44.4% → 21.5%). **품질(페이지 일관성)은 재지 않았으므로** 비싼 쪽을 켤 근거가
  없다. 일관성 지표가 생기면 다시 본다
- **`FULL_AR` 는 이 하드웨어의 부하 모델에서 쓸 수 없다** — 30 req/s 에서도 coverage 56.8%,
  e2e p95 305ms 로 SLO(300ms) 초과

## Unreleased — E4 후속: 제약의 원천을 실제 재고로 (#127 / M4)

### 추가

- **제약 원천을 실제 `stock` 테이블로 이었다** — `order`가 루트에 읽기 포트
  `StockAvailabilityFacts`를 열고, `recommendation`이 그것으로만 재고를 읽는다(ADR-018 방식).
  계약은 **fail-closed**(재고 행이 없거나 `quantity ≤ 0`이면 팔 수 없다) — 확인이 조용히 무력해지지
  않게 하려는 것이다. 화면용 `CatalogQueryService.idsInStock`(fail-open)과는 용도가 달라 합치지 않았다
- **추천 응답에 `checkMs`** — 확인에 쓴 시간을 따로 낸다. 모델 지연(50ms)에 묻혀 있던 확인 비용을
  드러내는 값이다(비용 축). 사전 스냅샷과 사후 확인 두 구간을 합쳐서 보고한다
- **실험 계기 엔드포인트 재편** — `/swap`(품절 크기를 보존한 채 구성만 교체)과 `/prime`(목표 세우기)을
  더하고, `consume`/`release` 단독 호출은 **없앴다**(따로 부르면 품절 집합 구성이 고정돼 위반율이
  정책 효과가 아니라 정렬을 재게 된다 — 실제로 그렇게 나왔다)
- **`V60__recommendation_experiment_stock.sql`** — 실험 풀 24개 id의 재고 행 시드(products 행은 만들지
  않아 상점에 안 뜬다)
- **E4b 실험 리포트** — `personalization/docs/runs/20260921-e4b-제약-재검증-실재고/`(8절 + 원자료).
  ADR-039

### 변경

- **확인 비용이 처음으로 측정됐다** — 읽기 1회 ≈ **3~4ms**(로컬 MySQL 왕복). `AT_GENERATION_START`
  (풀 24개를 미리 읽음)와 `AFTER_GENERATION`(출력 12개를 나중에 읽음)은 **둘 다 질의 1회**로 비용이
  같고, 다른 것은 **창**(58ms vs 3ms)뿐이다 — *"미리 읽으면 싸다"* 가 반증됐다. 기본값
  `AFTER_GENERATION`은 유지된다
- **품절 집합 크기를 6/24로 고정**해 위반율 순서를 정책 효과로 읽을 수 있게 했다(제어율 100%).
  `NONE` 63.14% > `AT_GENERATION_START` 29.16% ≫ `AFTER_GENERATION` 1.41% ≈ `AT_RESPONSE` 2.52%
- **E4가 적었던 `AFTER_GENERATION`의 위반율 `0.00%`는 공짜 확인의 산물이었다** — 실제 원천에서는
  **1.41%** 다(확인이 시간을 쓰므로 마지막 확인과 계기 사이에 간격이 생긴다)

## Unreleased — 제약 재검증 시점 (#127 / M4)

### 추가

- **제약 확인 시점 정책 4종**(`app.recommendation.constraint-policy`) — `NONE` · `AT_GENERATION_START` ·
  **`AFTER_GENERATION`(기본)** · `AT_RESPONSE`. 확인 대상·방법은 하나를 공유하고(`ConstraintChecker`)
  **시점만** 바뀐다
- **추천 응답이 창을 밝힌다** — `snapshotAgeMs`(확인에 쓴 사실이 응답 시점에 얼마나 낡았나) ·
  `changesInWindow`(그 창에서 바뀐 사실 수) · `filteredByConstraint` · `violations`(바깥에서 다시
  대조한 위반 수). `servingMs`(정책 경로)와 `auditMs`(계기)를 나눠서 준다
- **E4 실험 리포트** — `personalization/docs/runs/20260921-e4-제약-재검증/`(8절 + 원자료).
  하네스: `k6/constraint-revalidation.js` · `tools/run-constraint-revalidation.sh` ·
  `tools/constraint_report.py`
- **이슈 템플릿에 3필드**(주요 불확실성 · 예상 완료 · 예상 산출물) — 예측하고 고치는 습관을 남기기 위해

### 변경

- **기본 제약 확인 시점이 `NONE` → `AFTER_GENERATION`으로 바뀌었다.** E4가 사전 등록한 규칙
  ("응답 직전 확인과 생성 후 확인의 위반율 차이가 1%p 미만이면 싼 쪽을 기본으로")이 발동했다 —
  실측: 둘 다 위반율 0.00%, 응답 직전 확인은 읽기를 한 번 더 쓴다. 근거와 못 잰 것은
  [ADR-038](docs/adr/ADR-038-constraint-revalidation-timing.md)
- **개인화 화면의 E4 fixture를 실측으로 교체**하고, E3도 측정이 끝났으므로 목록에서 `측정 완료`로 올렸다

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
