# 설계 결정 기록(ADR) 색인

결정 86개를 영역별로 묶은 색인이다.
번호는 코드 주석과 블로그 글이 인용하므로 바꾸지 않는다.
결정이 바뀌면 새 ADR로 남기고 옛 ADR의 상태 줄에 대체됨을 적는다.

결제 핵심부터 읽는다면 [ADR-022](ADR-022-pg-brownout-resource-limits.md) → [ADR-057](ADR-057-recovery-backoff-over-order.md) → [ADR-069](ADR-069-payment-integrity-invariants.md) → [ADR-003](ADR-003-stock-deduction-timing.md) 순서가 빠르다.

| 상태 | 뜻 |
|---|---|
| 채택 | 지금 코드와 기본값에 들어 있다 |
| 비교 실험 | 선택지를 같은 조건에서 재 보았고 운영 기본값에는 배선하지 않았다 |
| 기각 · 채택 안 함 | 만들거나 재 보았지만 기본으로 켜지 않는다 |
| 대체됨 · 개정 | 뒤의 ADR이나 구현이 결정을 바꿨다 |
| 미결 | 설계만 정했고 숫자는 정하지 않았다 |

## 결제와 PG

PG 장애 격리, 결과 모르는 결제의 확정, 결제 정합성

| ADR | 결정 | 상태 |
|---|---|---|
| [022](ADR-022-pg-brownout-resource-limits.md) | 느린 PG 앞에서 무엇을 먼저 포기할 것인가 | 채택 |
| [057](ADR-057-recovery-backoff-over-order.md) | 미확정 복구는 확정 못 한 건을 뒤로 민다: 순서를 바꿔서는 풀리지 않았다 | 채택 |
| [058](ADR-058-shed-browse-before-webhook.md) | 자원이 마르면 조회를 먼저 돌려보낸다: 웹훅은 PG 상한만으로는 지켜지지 않았다 | 채택 |
| [069](ADR-069-payment-integrity-invariants.md) | 결제 한 건이 모듈을 건너며 맞는지 불변식으로 센다 | 채택 |
| [086](ADR-086-net-cancel-retry-window-over-auth-capture-split.md) | 망취소는 자동 매입을 유지한 채 시간 창 동안 재시도할 수 있게 하고, 승인 · 매입 분리는 만들지 않는다 | 채택 |
| [087](ADR-087-partial-cancel-refund-order-internal-first.md) | 복합결제 부분취소는 포인트 → 월렛 → 카드 순으로 돌려준다 | 채택 |
| [030](ADR-030-dlt-replay-and-post-recovery-reconciliation.md) | 격리·발견·복구·검증을 따로 만든다 | 채택 |
| [007](ADR-007-checkout-transaction-boundary.md) | 체크아웃 트랜잭션 경계: 단일 트랜잭션 → 3단계 사가로 이행 | 개정 |
| [019](ADR-019-pci-scope-by-not-touching-cards.md) | 카드번호를 서버가 만지지 않아 카드 데이터 환경의 범위를 줄인다 | 채택 |
| [020](ADR-020-multi-pg-routing-off-by-default.md) | 멀티 PG 라우팅을 만들어 두고 기본으로 끈다 | 채택 |
| [076](ADR-076-webhook-isolation-shuffle-sharding.md) | 판매자별 웹훅 발송 격리 — 공유 풀 대 고정 샤드 대 셔플 샤딩 대 셔플+재시도 | 비교 실험 |

## 재고와 주문

재고 차감 시점과 잠금, 애그리거트 경계

| ADR | 결정 | 상태 |
|---|---|---|
| [003](ADR-003-stock-deduction-timing.md) | 재고는 결제 승인 성공 시점에 차감한다 | 채택 |
| [004](ADR-004-stock-deduction-locking.md) | 재고 차감은 조건부 UPDATE 전략을 쓴다 | 채택 |
| [017](ADR-017-aggregate-boundaries.md) | 주문·상품·재고를 각각 애그리거트로 두고 ID 로만 잇는다 | 채택 |
| [078](ADR-078-isolation-level-implicit-repeatable-read.md) | 트랜잭션 격리 수준 — MySQL 기본 REPEATABLE READ 에 둔다 | 채택 |

## 원장 · 정산 · 대사

복식부기 원장, 정산 집계와 분리, 대사 기록

| ADR | 결정 | 상태 |
|---|---|---|
| [013](ADR-013-cancellation-as-separate-recon-row.md) | 취소를 대사에 별도 행으로 쌓는다 (덮어쓰지 않는다) | 채택 |
| [016](ADR-016-money-with-currency.md) | 금액에 통화를 넣고, 통화가 다르면 연산하지 않는다 | 채택 |
| [023](ADR-023-settlement-earned-date-vs-swept-date.md) | 정산 집계일과 거래 귀속일을 분리할 것인가 | 채택 |
| [024](ADR-024-settlement-extraction-data-cutover.md) | 정산 분리에서 과거 데이터를 어떻게 넘길 것인가 | 채택 |
| [025](ADR-025-ledger-balance-read-strategy.md) | 원장 잔액을 어떻게 읽을 것인가 | 채택 |
| [088](ADR-088-hot-account-buffered-posting-over-split.md) | 핫 계정이 생기면 잔액 행을 분개마다 갱신하지 않고 한 집계기가 모아 반영한다. 하위 계정 분할은 만들지 않는다 | 채택 |
| [027](ADR-027-settlement-fairness-policy.md) | 큰 가맹점 하나가 전체를 늦출 때 (정산 공정성 정책) | 채택 안 함 |
| [029](ADR-029-deployment-unit-vs-service-boundary.md) | 실행 단위만 분리하는 것과 저장소까지 분리하는 것 | 채택 |
| [073](ADR-073-settlement-batch-partition-strategy.md) | 정산 배치 병렬화 — 나누는 키(모듈러 대 항목 id 범위 대 판매자 크기 묶음) | 비교 실험 |
| [015](ADR-015-subscription-billing-anchor.md) | 다음 청구일을 직전 청구일이 아니라 앵커에서 계산한다 | 채택 |

## 운영 도구

대사 불일치 분석, 주문 타임라인, 상담 초안

| ADR | 결정 | 상태 |
|---|---|---|
| [008](ADR-008-recon-mismatch-analysis-assistant.md) | 대사 불일치 확정에 사유를 남기고, 그 위에 1차 분석기를 얹는다 | 대체됨 |
| [011](ADR-011-order-timeline-assembly.md) | 주문 한 건의 전 과정을 조립해 보여준다 | 채택 |
| [012](ADR-012-rule-based-cause-classifier.md) | 대사 불일치 원인을 규칙으로 제안한다 (AI 없이) | 채택 |
| [014](ADR-014-cs-draft-port-and-number-guard.md) | 상담 초안: 포트를 먼저 만들고 모델은 나중에 | 채택 |

## 보안과 규제

암호화, 비밀번호, AML, FDS

| ADR | 결정 | 상태 |
|---|---|---|
| [006](ADR-006-envelope-encryption-dek-kek.md) | 필드 암호화를 Envelope Encryption(DEK/KEK)으로 확장한다 | 채택 |
| [009](ADR-009-password-hashing-and-migration-path.md) | 비밀번호 해시를 Argon2id로 옮기고, 기존 해시는 그대로 검증한다 | 채택 |
| [021](ADR-021-no-aml-screening-by-not-holding-identity.md) | 신원 정보를 안 갖고 있어 AML 스크리닝을 붙이지 않는다 | 개정 |
| [028](ADR-028-fds-threshold-with-delayed-labels.md) | FDS 임계값을 운영에서 어떻게 움직일 것인가 | 미결 |

## 아키텍처와 이벤트

모듈 구조, 아웃박스, Kafka 외부화, 스키마 진화

| ADR | 결정 | 상태 |
|---|---|---|
| [001](ADR-001-architecture-spring-modulith.md) | 아키텍처로 Spring Modulith를 채택한다 | 채택 |
| [002](ADR-002-outbox-event-publication-registry.md) | Transactional Outbox를 Spring Modulith Event Publication Registry로 구현한다 | 채택 |
| [005](ADR-005-event-externalization-kafka.md) | 결제 이벤트를 Kafka로 외부화한다 (Spring Modulith `@Externalized`) | 채택 |
| [010](ADR-010-what-not-to-build.md) | 무엇을 만들지 않을지 정하는 기준 | 채택 |
| [018](ADR-018-module-internal-packages.md) | 모듈 루트에는 API만 두고 나머지는 `internal`로 내린다 | 채택 |
| [026](ADR-026-event-schema-evolution.md) | 이벤트 스키마를 바꿔야 할 때 | 채택 |
| [047](ADR-047-cdc-for-activity-not-outbox.md) | CDC 는 활동 로그에 쓰고, 아웃박스 릴레이는 그대로 폴링으로 둔다 | 채택 |
| [049](ADR-049-repo-layout-by-area.md) | 저장소를 영역으로 나누되, 같이 쓰는 것은 루트에 둔다 | 채택 |
| [080](ADR-080-media-gradle-submodule.md) | media를 별도 Gradle 모듈로 떼면서 순환을 막는 법 | 채택 |

## 장애 대응 비교 실험

운영 기본값에는 배선하지 않고 비교만 한 실험

| ADR | 결정 | 상태 |
|---|---|---|
| [070](ADR-070-cache-expiry-stampede-single-flight.md) | 인기 상품 캐시 만료 몰림 — lease/single-flight 대 나이브 TTL | 비교 실험 |
| [071](ADR-071-batch-worker-lease-vs-row-claim.md) | 배치 워커 두 대 — 리스 리더 대 행 단위 나눠 갖기 | 비교 실험 |
| [072](ADR-072-restart-warmup-ramp-vs-immediate.md) | 재시작 직후 웜업 — 트래픽 한 번에 대 서서히 | 비교 실험 |
| [074](ADR-074-search-reindex-interval.md) | 검색 전체 재색인 주기 — 이벤트 유실 대비 신선도 대 재색인 비용 | 비교 실험 |
| [075](ADR-075-cache-invalidation-fill-race.md) | 캐시 채우기와 무효화의 경합 — lease 없음 대 lease 대 버전 비교 | 비교 실험 |
| [077](ADR-077-sse-fanout-broadcast-vs-routing.md) | 여러 대 실시간 알림 전파 — 전체 방송 대 세션 위치 라우팅 | 비교 실험 |

## 쇼핑몰 화면과 개인화

카탈로그, 홈 구성, 추천, GenPage, A/B

| ADR | 결정 | 상태 |
|---|---|---|
| [031](ADR-031-storefront-catalog-read-api.md) | 쇼핑몰 카탈로그를 읽기 전용으로 연다 | 채택 |
| [032](ADR-032-frontend-nextjs-adoption.md) | 프론트 스택을 Next.js로 전환한다 | 채택 |
| [033](ADR-033-wishlist-server-side.md) | 찜을 서버에 저장하고 로그인을 요구한다 | 채택 |
| [034](ADR-034-personalization-context-deployment-unit.md) | 개인화 온라인 컨텍스트를 같은 배포 단위에 둔다 | 채택 |
| [035](ADR-035-order-tolerant-context-merge.md) | 컨텍스트 병합을 순서에 관대하게 만든다 — 늦게 온 이벤트를 버리지 않고 제자리에 넣는다 | 채택 |
| [036](ADR-036-context-window-aggregate.md) | 창 집계를 목록과 분리한다 — 세는 일에는 세는 그릇을 준다 | 채택 |
| [037](ADR-037-overload-admission-policy.md) | 과부하에서 무엇을 포기하는가 — 정책은 지연을 사고, coverage는 모델이 정한다 | 채택 |
| [038](ADR-038-constraint-revalidation-timing.md) | 제약을 언제 다시 확인하는가 — 추천은 stale을 허용하고 구매는 강보장한다 | 채택 |
| [039](ADR-039-constraint-source-real-stock.md) | 제약의 원천을 실제 재고로 잇는다 — 추천이 커머스 재고를 읽는 경계 | 채택 |
| [040](ADR-040-generation-scope-and-budget.md) | 홈 페이지를 어떻게 만드는가 — 생성 범위는 비용을 사고, 품질은 재지 않았다 | 채택 |
| [041](ADR-041-cache-compression-threshold.md) | 캐시 압축은 1KB부터 — 근거는 생겼고 적용할 자리는 아직 없다 | 채택 |
| [042](ADR-042-home-composer-boundary.md) | 홈 컴포저를 별도 경계로 둔다 — 페이지는 추천의 합이 아니다 | 채택 |
| [043](ADR-043-home-impression-log.md) | 홈 노출은 요청당 한 행으로 남긴다 — 관측을 도메인 이벤트에 태우지 않는다 | 채택 |
| [045](ADR-045-real-popularity-and-refill.md) | 인기 신호를 실제 통계로 바꾸고, 되채우기를 켠다 — 신호를 바꾸면 화면이 짧아진다 | 채택 |
| [046](ADR-046-synthetic-reviews.md) | 합성 리뷰를 쓰되, 집계하지 않는다 — 읽는 것이지 재는 것이 아니다 | 채택 |
| [048](ADR-048-als-model-not-adopted.md) | 학습한 ALS 모델을 넣지 않는다 — 기준선을 못 넘었다 | 기각 |
| [050](ADR-050-post-filter-over-constrained-generation.md) | 제약은 생성 뒤에 거른다 — 생성 중 차단은 이득이 안 나왔고 대가는 6배였다 | 기각 |
| [052](ADR-052-home-pagination-cursor.md) | 홈 다음 쪽은 커서가 보여 준 것을 들고 다니고, 순서는 요청 시점의 세션이 정한다 | 채택 |
| [053](ADR-053-genpage-mini-not-default.md) | 작은 GenPage 모델을 만들어 서빙까지 붙이고, 기본값으로는 켜지 않는다 | 기각 |
| [054](ADR-054-genpage-validation-tuning.md) | GenPage 를 검증 기간으로 다시 튜닝했고, 결론은 그대로다 | 기각 |
| [060](ADR-060-genpage-serves-purchases.md) | GenPage 추천 행은 구매 이력으로 서빙하고, 재구매 우선 혼합을 A/B 후보로 둔다 | 채택 |
| [061](ADR-061-ab-assignment-and-attribution.md) | A/B 배정은 해시로 고정하고, 노출은 홈 기록에 얹고, 귀속은 노출된 상품으로 한정한다 | 채택 |
| [065](ADR-065-admission-by-observed-throughput.md) | 과부하 게이트는 관측한 처리량으로 대기를 추정하고, 기본 정책을 ADMISSION 으로 맞춘다 | 채택 |
| [066](ADR-066-next-page-follows-purchase-history.md) | 홈 다음 쪽의 GenPage 입력은 추천 행과 같은 이력을 따르고, 구매를 쓸 때는 구매만 넣는다 | 채택 |
| [067](ADR-067-genpage-page-shares-capacity.md) | 홈 다음 쪽의 GenPage 호출도 추천 행과 같은 동시 호출 자리를 쓰고, 자리가 없으면 기다리지 않는다 | 채택 |
| [068](ADR-068-genpage-v2-not-default.md) | 넷플릭스 GenPage 를 옮긴 v2 도 선을 넘지 못해 기본으로 켜지 않는다. 백엔드 경로는 v2 로 다시 쟀다 | 채택 |
| [079](ADR-079-genpage-hybrid-default-on.md) | 홈 2쪽 줄 구성 기본값을 결합 방식(HYBRID)으로 켠다 | 채택 |

## 검색

검색 엔진 선택, 필터, 색인 반영, 동점 처리

| ADR | 결정 | 상태 |
|---|---|---|
| [044](ADR-044-no-search-engine-yet.md) | 검색 엔진을 지금 도입하지 않는다 — 패싯 사전 집계를 먼저 한다 | 대체됨 |
| [051](ADR-051-search-engine-by-quality.md) | 검색은 앱 안 Lucene 으로 한다: 품질 축을 재니 MySQL 이 못 따라왔다 | 채택 |
| [055](ADR-055-search-filters-in-engine-and-scale-limit.md) | 필터·패싯은 검색 엔진 안에서 건다. 앱 안 Lucene 의 전환점은 300만 건이다 | 채택 |
| [056](ADR-056-search-index-freshness-by-cdc.md) | 검색 색인은 CDC 로 반영한다. 엔진은 앱 안 Lucene 을 유지한다 | 채택 |
| [059](ADR-059-cdc-poll-and-health.md) | CDC 는 poll 100ms 를 유지하고, 떠 있는지와 흐르는지를 따로 감시한다 | 채택 |
| [062](ADR-062-search-tie-break-by-id.md) | 검색 동점은 상품 id 로 끊는다: 기준(0)은 못 넘었지만 흔들림을 99% 줄였다 | 채택 |
| [063](ADR-063-query-rewrite-not-default.md) | 검색어 고치기를 만들었지만 기본으로 켜지 않는다: 한국어는 부서명 사전으로는 모자랐다 | 기각 |
| [064](ADR-064-engine-tie-break.md) | ES·OpenSearch 도 동점을 상품 id 로 끊는다: 두 엔진의 차이는 전부 동점 순서였다 | 채택 |

## 숏폼과 라이브

미디어 모듈, 변환, 라이브 방송, 방송 중 주문

| ADR | 결정 | 상태 |
|---|---|---|
| [081](ADR-081-shorts-transcode-outbox-worker-profile.md) | 숏폼 변환 2단계: MinIO·Kafka·별도 FFmpeg 워커 대신 로컬 저장소·Outbox·같은 jar worker 프로파일 | 채택 |
| [082](ADR-082-live-broadcast-outbox-hooks.md) | 라이브 방송 1단계: MediaMTX 출발점에서 Kafka만 빼고 나머지는 그대로 쓴다 | 채택 |
| [083](ADR-083-shorts-signal-path.md) | 숏폼 시청 신호는 media 자신의 표에 남긴다 — 커머스의 개인화 Kafka/CDC 경로를 재사용하지 않는다 | 채택 |
| [084](ADR-084-live-pin-sync-via-program-date-time.md) | 고정 상품 카드 동기화는 HLS PROGRAM-DATE-TIME으로, 전달은 Outbox 대신 즉시 WebSocket으로 | 채택 |
| [085](ADR-085-live-order-redis-reservation-mysql-confirmation.md) | 방송 중 "바로 주문"은 Redis 선점(TTL) 게이트 + 기존 commerce 주문·결제 흐름 재사용으로 만든다 | 채택 |
