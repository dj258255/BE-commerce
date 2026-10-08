# ADR-085. 방송 중 "바로 주문"은 Redis 선점(TTL) 게이트 + 기존 commerce 주문·결제 흐름 재사용으로 만든다

- 상태: **채택**
- 날짜: 2026-10-09
- 관련: R10·R11·R12(R13·R14·R15는 다음 단계), R32,
  [42-라이브커머스-숏폼-명세.md](../42-라이브커머스-숏폼-명세.md) §5,
  [ADR-003](ADR-003-stock-deduction-timing.md)(재고 차감 시점),
  [ADR-004](ADR-004-stock-deduction-locking.md)(재고 차감 락),
  [ADR-039](ADR-039-constraint-source-real-stock.md)(실 재고를 원천으로),
  [ADR-018](ADR-018-module-internal-packages.md)(모듈 내부 패키지),
  [ADR-080](ADR-080-media-gradle-submodule.md)(media Gradle 서브모듈),
  [ADR-084](ADR-084-live-pin-sync-via-program-date-time.md)(R8·R9 고정)

## 맥락

R10~R12는 고정 상품 카드의 "바로 주문"을 다룬다. 핵심 제약 셋:

- **R10**: 가능 여부·금액은 클라이언트가 보낸 값이 아니라 서버의 고정 상태(`LivePin`)로 판정한다.
- **R11**: 장바구니를 거치지 않는다. 주문 생성·결제는 **기존 commerce 흐름을 그대로 호출**하고,
  media에는 주문·결제·재고 확정 로직을 두지 않는다(R32).
- **R12**: 방송 특가 한정 수량 N에 동시 주문이 몰려도 확정 주문 수량 합이 N을 넘지 않는다.
  같은 멱등 키로 다시 와도 주문이 두 번 생기지 않는다.

ADR-003·ADR-004가 이미 **카탈로그 재고**(`stock` 테이블, 상품당 하나)를 지키는 문제를
풀어 뒀다 — 조건부 UPDATE로 원자적으로 차감하고(ADR-004), 승인 시점에 차감하거나
한정 상품은 `AT_PAYMENT`로 선점한다(ADR-003). 하지만 R12가 거는 한도는 그것과 **다른
대상**이다: "이 방송에서 이 상품에 건 한정 수량 N"은 카탈로그 재고와 별개로 판매자가 방송마다
임의로 정하는 **프로모션 한도**다(카탈로그 재고가 10,000개여도 방송 특가는 50개로 제한할 수
있다). 명세 5절이 이 둘을 분리해서 적은 이유이기도 하다: "방송-상품 한도를 Redis에서
선점(TTL) → 기존 MySQL 재고 차감으로 확정."

## 결정 1 — 두 한도를 분리한 채 둘 다 지킨다(둘 다 독립적으로 통과해야 주문이 만들어진다)

| 한도 | 저장소 | 지키는 쪽 | 바뀌는 곳 |
|---|---|---|---|
| 방송-상품 한정 수량 N | Redis ZSET(`live:pin:{broadcastId}:holds`) | **media**(`LiveOrderGate`, 새로 만듦) | 이 ADR |
| 카탈로그 재고 | MySQL `stock` | **commerce**(`StockReservationService`/`StockDeductionService`) | 손대지 않음 |

`LiveOrderService.order(...)`는 먼저 Redis 게이트를 통과한 요청만 commerce 주문 생성을
부른다. commerce 쪽은 평소와 똑같이 `CheckoutService`의 재고 전략(`CHECK`/`AT_ORDER`/...)을
그대로 탄다(§"결정 3" 참고) — **두 레이어 모두 독립적으로 통과해야** 주문이 만들어진다
(defense in depth). 하나가 뚫려도(예: Redis 게이트에 버그가 있어도) 카탈로그 재고가
없으면 여전히 거절된다.

## 결정 2 — Redis 선점은 Lua 스크립트 ZSET, 멤버 키 = 멱등 키, 실패 시 fail-closed

```lua
-- KEYS[1] = live:pin:{broadcastId}:holds
-- ARGV = [member(=멱등 키), now_ms, ttl_ms, limit]
local member = ARGV[1]
local now = tonumber(ARGV[2])
local expireAt = now + tonumber(ARGV[3])
if redis.call('ZSCORE', KEYS[1], member) then           -- 같은 멱등 키 재시도
  redis.call('ZADD', KEYS[1], expireAt, member)          -- TTL만 늘리고 성공
  redis.call('PEXPIRE', KEYS[1], ARGV[3])
  return 1
end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)     -- 지난 만료는 걷어낸다(수동적 반환)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
  return 0                                               -- 매진
end
redis.call('ZADD', KEYS[1], expireAt, member)
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
```

**멤버 키를 멱등 키 그대로 쓴 이유(R12.2)**: 같은 멱등 키로 재시도가 오면 "이미 멤버인가"를
먼저 보고 TTL만 늘린다 — 슬롯을 두 번 쓰지 않는다. 그래서 Redis 게이트 자체가 멱등하고,
뒤에 오는 commerce의 멱등 처리(결정 4)와 **같은 키로 독립적으로** 멱등을 보장한다 — 한쪽이
없어도 다른 쪽이 중복 생성을 막는 이중 안전장치다.

**score = 만료 시각(epoch ms), 매 호출마다 `ZREMRANGEBYSCORE`로 지난 만료를 걷어내는 이유**:
이게 "미결제 선점 수량 반환"(R13)의 **수동적** 형태다 — 능동적 해제 로직이 없어도, TTL이 지난
홀드는 다음 호출이 왔을 때 자동으로 비워진다. R13이 올 때는 결제 완료·취소 시 **즉시** 비우는
능동적 해제만 더하면 된다(이 ZSET 구조 자체를 바꿀 필요가 없다) — "이번 설계가 R13을 막지
않는다"는 요구를 이 lazy-cleanup 구조로 만족한다.

**TTL 기본값 5분**: 명세의 가정("수량 선점 TTL 기본값은 5분이며 결제 UNKNOWN은 예외로
유지된다")과 맞춘다 — R13이 올 때 그 "5분 뒤 반환" 규칙을 그대로 이 TTL에 대응시킬 수 있게
지금부터 같은 수치를 쓴다. `app.live.order.hold-ttl`로 바꿀 수 있다.

**Redis 장애 시 fail-closed — `RedisVelocityCounter`(commerce, fail-open)와 의도적으로
반대다**:

| | fail-open(`RedisVelocityCounter`) | fail-closed(`LiveOrderGate`, 이 ADR) |
|---|---|---|
| 상황 | 이상거래 속도 제한 | 한정 수량 선점 |
| 실패 시 | 통과시킨다(1로 집계) | 거절한다(예외를 그대로 던진다) |
| 근거 | 못 세면 통과시켜도 가용성이 이득 — 과금·결제를 막지 않는다 | 못 세면 N을 넘길 수 있다 — **R12의 핵심 불변식**(확정 합계 ≤ N)이 가용성보다 우선한다 |

## 결정 3 — commerce 쪽: 새 엔티티·테이블 없이 기존 주문 흐름에 "가격 오버라이드 경로"만 더한다

`OrderItem.of(productId, productName, unitPrice, quantity)`는 원래부터 카탈로그 조회 없이
명시적 단가를 받을 수 있었다(엔티티 자체는 가격 오버라이드가 가능했다 — `CheckoutService
.createOrder`의 호출부만 카탈로그 가격에 고정돼 있었을 뿐). 그래서 **`CheckoutService`에
새 메서드 하나**(`createSpecialPriceOrder(userId, productId, unitPrice)`)만 추가하고,
재고 보호·주문 저장 로직(`buildAndSaveOrder`로 추출)은 기존 `createOrder`와 **100% 공유**한다
— 수량은 늘 1(§"버린 대안" 참고)이고, 가격은 호출자(media)가 넘긴 값을 그대로 쓰되 상품
실존은 다시 확인한다(카탈로그에서 사라진 상품으로 주문이 만들어지지 않게).

이 메서드는 **대기열 게이트**(이벤트 상품 전용 선착순)는 타지 않는다 — 그건 다른 프로모션
기구이고, 방송 한정 수량은 R12의 Redis 게이트가 이미 본다. 반대로 **재고 전략
(`StockReservationService`의 CHECK/AT_ORDER/AT_PAYMENT)은 그대로 적용된다** — 방송
특가 주문도 결국 카탈로그 재고를 쓰는 진짜 주문이다.

새 MySQL 테이블·Flyway 마이그레이션은 필요 없다 — `LivePin`(ADR-084)과 기존 `orders`/
`stock`/`idempotency_records` 테이블만으로 충분하다.

## 결정 4 — 멱등(R12.2)은 기존 `IdempotencyService`를 그대로 재사용한다(media에 새로 만들지 않는다)

`IdempotencyService`는 `order.idempotency` 패키지의 **order 모듈 내부** 컴포넌트라 media가
직접 부를 수 없다(모듈 경계, ADR-018). 그래서 order 모듈이 스스로 공개 진입점을 하나 내준다 —
`ProductCatalogFacts` 같은 **읽기** 파사드가 이미 있는 자리에, 이번엔 **쓰기** 파사드
`SpecialPriceOrderPlacement`(order 루트 패키지)를 더했다. 이 파사드 안에서(= order 모듈
내부에서는 경계 제약이 없다) `IdempotencyService.execute(key, "/internal/live-order",
"POST", {userId, productId, unitPrice}, ..., () -> checkoutService
.createSpecialPriceOrder(...))`로 감싼다 — `/payments/confirm`이 쓰는 것과 **같은
primitive**다. 키는 같은데 productId·unitPrice가 다르면 기존 규칙 그대로 422
(`IDEMPOTENCY_KEY_REUSED`)로 거절된다.

**media 쪽엔 멱등 저장소를 새로 두지 않는다** — Redis 게이트의 "멤버=멱등 키" 재진입 처리
(결정 2)가 **같은 요청이 같은 슬롯을 두 번 못 쓰게**는 막아 주지만, "commerce 호출이 이미
성공해 주문이 있다"는 사실 자체는 `IdempotencyService`가 갖고 있다. 두 메커니즘이 **같은
멱등 키로 독립적으로** 중복을 막으므로(결정 2 참고) 어느 한쪽만으로도 R12.2가 깨지지 않는다.

### 경계: media(`live.OrderPlacement`) ↔ commerce(`order.SpecialPriceOrderPlacement`)

```
media(live)                              commerce
──────────────────────                   ──────────────────────────────
LiveOrderService                         (경계: 모듈별 allowedDependencies)
  → LiveOrderGate (Redis, R12)
  → OrderPlacement (포트, media가 정의)  ← LiveOrderPlacementAdapter (구현, commerce 물리 위치)
                                                → order.SpecialPriceOrderPlacement (공개 진입점)
                                                    → IdempotencyService (order 내부)
                                                    → CheckoutService.createSpecialPriceOrder (order 내부)
                                                        → StockReservationService (order 내부, 손대지 않음)
```

`ProductLookup`/`LiveProductLookupAdapter`(R8)와 완전히 같은 모양(split package, 인터페이스는
media에 정의, 구현은 commerce 쪽 같은 패키지 이름 파일)이다 — 새 패턴을 만들지 않았다.

## 결정 5 — Redis는 선점했는데 commerce 확정이 실패하면: 즉시 해제(release)하고 거절을 그대로 올린다

이게 "Redis 선점(TTL)과 MySQL 확정의 경계에서 무엇이 어긋날 수 있는가"의 핵심 질문이다.

**일어날 수 있는 어긋남**: `LiveOrderGate.tryReserve`가 슬롯을 내줬는데(Redis N 한도 통과),
그 직후 `OrderPlacement.place`(commerce 호출)가 실패하는 경우 — 예를 들어 상품이 그 사이
카탈로그에서 삭제됐거나(`PRODUCT_NOT_FOUND`), 카탈로그 재고가 바닥났거나(`OUT_OF_STOCK`,
Redis N 한도와 무관하게 실제 재고가 먼저 동나는 경우), `IdempotencyService`가 키 재사용
불일치로 거절하거나(`IDEMPOTENCY_KEY_REUSED`), MySQL 자체 장애.

**처리**: `LiveOrderService.order`가 `orderPlacement.place(...)`를 `try/catch
(RuntimeException)`로 감싸고, 어떤 예외든 **`gate.release(broadcastId, idempotencyKey)`로
그 슬롯을 즉시 돌려준 뒤 원래 예외를 그대로 다시 던진다.** 결과:

- 그 슬롯은 **낭비되지 않는다** — 바로 다음 요청이 같은 N 중 하나를 다시 쓸 수 있다(TTL
  만료를 기다릴 필요가 없다).
- 호출자(클라이언트)에게는 commerce가 준 실패 사유가 **그대로** 올라간다(예:
  `OUT_OF_STOCK` 409) — media가 "확정 실패"를 다른 말로 가리지 않는다.
- 같은 멱등 키로 **재시도**가 오면: Redis 게이트는 멤버가 없으니(해제됐으므로) 다시
  선점을 시도하고, `IdempotencyService`는 — 그 키로 "성공한" 기록이 없으므로(실패한
  시도는 레코드를 완료 상태로 안 남긴다, 기존 `IdempotencyService` 규약) — 다시 처음부터
  실행한다. **"확정 안 된 선점은 영원히 슬롯만 차지하고 아무 주문도 없는" 상태가 안 남는다.**

**반대로 고려했지만 버린 처리**:

| 대안 | 왜 버렸나 |
|---|---|
| 실패해도 슬롯을 그대로 둔다(TTL 만료만 기다림) | 상품 삭제·일시 카탈로그 재고 소진처럼 **금방 다시 시도하면 될 수 있는** 실패까지 5분을 묶어 둔다 — N이 작을수록(50개) 이 낭비가 매진 오판을 만든다(실제로는 48개만 나갔는데 게이트는 50 다 찬 것처럼 보임) |
| Redis 게이트 자체를 2단계(soft-hold → commerce 성공 후 confirm)로 나눠 성공 전까지는 카운트하지 않는다 | 그러면 두 요청이 "아직 soft-hold 상태"인 채로 동시에 commerce를 호출할 수 있어 N을 넘기기 쉬워진다 — **선점 자체가 카운트에 들어가야** R12의 "확정 합계 ≤ N"이 경합 중에도 성립한다(홀드=즉시 카운트, 이게 R12.1의 핵심) |

## R13·R14·R15을 막지 않는다는 근거

- **R13(미결제 반환, 기본 5분)**: 이 TTL 구조가 이미 "수동적" 반환이다 — 능동적 반환(결제
  완료/취소/만료 이벤트를 구독해 즉시 `gate.release` 호출)만 더하면 된다. ZSET·Lua 스크립트를
  바꿀 필요가 없다. `결제 UNKNOWN 중 수량 유지`도 마찬가지다 — UNKNOWN인 동안은 그냥
  `release`를 호출하지 않으면 된다(TTL이 지나도 유지하려면 능동적으로 TTL을 갱신하는
  한 줄만 더하면 된다, `ZADD`로 score를 다시 미래로 미는 것과 같은 연산이다).
- **R14(매진 시 1초 내 전체 전환)**: 지금은 게이트 거절이 **그 요청을 보낸 사람에게만** 보인다.
  "매진이 됐다"는 사실을 전체 시청자에게 브로드캐스트하려면 `LiveOrderGate`가 매진 전이
  순간(ZCARD가 처음 limit에 도달하는 순간)을 감지해 `LivePinBroadcaster`(ADR-084, 이미
  있는 WebSocket 발행 경로)로 이벤트 하나를 더 내보내면 된다 — 새 전송 경로가 필요 없다.
- **R15(거절 p95 200ms)**: 이미 이 설계가 만족한다 — 게이트 거절은 **commerce를 전혀
  호출하지 않고**(`LiveOrderGate.tryReserve`가 `false`를 돌려주면 그 자리에서 예외를
  던진다) 끝난다. Redis 왕복 1회가 전부다.

## 버린 대안

| 대안 | 왜 버렸나 |
|---|---|
| 수량을 1로 고정하지 않고 클라이언트가 수량을 보내게 한다 | R12의 ZSET 게이트는 "멤버 수 ≤ N"만 원자적으로 본다 — 수량 N인 멤버 하나를 허용하려면 ZCARD 대신 가중합을 Lua로 다시 짜야 하고, 실제 한정판 라이브커머스도 보통 1인 1건이다(스캘핑 방지). 범위를 좁혀 ADR-004가 이미 증명한 "카디널리티 기반 원자 비교"를 그대로 재사용했다 |
| Redis 대신 MySQL `stock_reservations`(ADR-003의 `AT_PAYMENT`)를 방송 한도에도 그대로 씀 | 그건 **카탈로그 재고 한 벌**에 대한 장부다 — 같은 상품이 여러 방송에서 동시에 다른 한정 수량으로 걸릴 수 있다는 "방송-상품" 차원이 없다. 거기에 broadcast_id를 더해 테이블을 넓히는 것보다, 이미 명세가 못박은 대로 Redis를 새 차원으로 쓰는 쪽이 기존 재고 장부를 안 건드린다 |
| media가 `IdempotencyService`와 같은 멱등 기구를 자체 테이블로 새로 만든다 | R32가 금지하는 "주문 로직 중복 구현"과 정신이 같다 — 멱등은 주문 생성의 일부이지 media만의 관심사가 아니다. 기존 primitive를 공개 파사드로 열어 재사용하는 쪽이 전부 한 곳(order 모듈)에 멱등 규칙이 머물게 한다 |
| commerce가 HTTP로 `/payments`류 엔드포인트를 media에 **노출**(media가 HTTP 호출) | media·commerce는 같은 JVM·같은 jar(ADR-080)다 — 프로세스 내 호출을 굳이 HTTP 왕복으로 바꿀 이유가 없다. 포트/어댑터(결정 4)로 충분하다 |

## 구현 요약

- **media**: `live.OrderPlacement`(포트)·`live.LiveOrderException`·`live.LiveOrderGate`
  (Redis Lua 게이트)·`live.LiveOrderService`(R10 판정 + R12 게이트 + R11 위임)·
  `live.web.LiveOrderController`(`POST /api/v1/live/broadcasts/{id}/orders`, ROLE_USER).
- **commerce**: `order.SpecialPriceOrderPlacement`(공개 쓰기 진입점, 멱등 래핑)·
  `order.internal.CheckoutService#createSpecialPriceOrder`·`live.LiveOrderPlacementAdapter`
  (포트 구현, split package)·`SecurityConfig`에 `POST .../orders` → `ROLE_USER` 규칙 추가·
  `GlobalExceptionHandler`에 `LIMITED_QUANTITY_SOLD_OUT` → 409 매핑 추가.
- 새 Flyway 마이그레이션 없음(기존 테이블로 충분).
- 결제는 이 흐름이 돌려준 `orderNo`·`totalAmount`로 화면이 기존
  `POST /api/v1/payments/confirm`을 그대로(변경 없이) 호출한다(R11).

## 검증

- 단위(media, `LiveOrderServiceTest`, 5건 전부 통과): R10.1(서버 가격 사용)·R10.2(고정
  불일치/해제 시 409, 주문·게이트 미호출)·R12/R15(게이트 거절 시 commerce 미호출)·
  R12(확정 실패 시 release).
- 실 동시성(commerce, `LiveOrderConcurrencyTest`, `@Tag("integration")`, Testcontainers
  MySQL+Redis): R12.1(스레드 N배 동시 주문 → 확정 합계 = N, 초과 거절) · R12.2(같은 멱등
  키 4회 → 주문 1건, 응답 4개 모두 같은 주문 id). **이 b-studio 샌드박스에는 Docker가 없어
  (`integrationTest`가 "Could not find a valid Docker environment"로 실패 — 이 저장소의
  기존 Testcontainers 기반 테스트 전부에 해당하는 샌드박스 제약이지 이 변경의 결함이 아니다)
  이 테스트 자체는 여기서 실행할 수 없었다.** 대신 아래 실 경로 확인으로 같은 불변식을
  실제 떠 있는 서버·MySQL·Redis로 확인했다.
- **실 경로(이번 턴에 실제로 실행, 2026-10-08)**: 이 샌드박스에는 k6가 설치돼 있지 않아
  `tools/run-live-order-stock.sh`(k6 기반, k6가 있는 환경에서 그대로 쓸 수 있도록 남겨 둠)
  대신 curl 백그라운드 프로세스로 동등한 동시 요청을 만들어 실제 commerce(+실 MySQL·Redis)에
  대고 확인했다:
  - R12.1: 방송 생성 → 실 RTMP 송출로 LIVE 전이 → 상품 1을 한정 5개로 고정 → 서로 다른
    계정 15명이 동시에 각 1건씩 주문 → **확정(201) 5건, 거절(409) 10건, 그 외 0건**
    (거절 응답 본문: `{"code":"LIMITED_QUANTITY_SOLD_OUT", ...}`).
  - R12.2: 같은 사용자·같은 멱등 키로 4번 연속 호출 → **네 응답 모두 201과 같은
    `orderNo`**(`01M4EDYQPE2BTD78Q5K8NXGJER`).
  - R10.2: 고정 해제 직후 그 상품으로 주문 → **409 `NOTHING_PINNED`**.
  - R5.2: 인증 헤더 없이 주문 호출 → **401**.
  - 명세의 실제 수치(한정 50개·동시 1,000명)는 k6가 있는 환경에서
    `LIMIT=50 VUS=1000 ./tools/run-live-order-stock.sh`로 그대로 재현할 수 있다 — 이번
    샌드박스 확인은 같은 메커니즘을 더 작은 배수(5·15)로 증명했다.

## 다시 볼 조건

- Redis가 완전히 죽으면(fail-closed) 그 방송의 "바로 주문"이 전부 거절된다 — 가용성보다
  정합성을 택한 결과다. Redis 이중화·Sentinel 구성은 이번 범위 밖이다.
- 수량 1 고정은 "한 사용자가 여러 번(다른 멱등 키로) 주문해 N개 중 여러 개를 가져가는 것"까지
  막지는 않는다 — "사용자당 한정"이 필요해지면 ZSET 멤버를 멱등 키 대신 `userId`로 바꾸는
  별도 규칙을 더해야 한다(이번 범위 밖, R12는 "총량 N"만 요구한다).
- R14(매진 전체 전환)는 위에서 설계만 제시했고 이번 턴에 구현하지 않았다 — 다음 단계.
- **같은 방송을 다시 고정(재고정)하면 Redis 홀드 키가 겹친다** — `live:pin:{broadcastId}:holds`는
  `broadcastId`로만 범위를 잡는다. 판매자가 한 상품을 완판시킨 뒤 **같은 방송에서** 다른 상품을
  새로 고정하거나(다른 "드롭"), 같은 상품을 다시 고정해 한도를 늘리면, 이전 드롭에서 쌓인
  홀드가 그대로 남아 새 한도를 갉아먹는다(예: 50개 드롭을 완판한 뒤 30개짜리 새 드롭을
  고정해도 게이트는 이미 50이 차 있다고 본다). 가격 변경(`changePrice`, 같은 드롭의 연장)은
  반대로 **그대로 유지돼야 하므로**, 단순히 `seq`로 범위를 잡는 것도 답이 아니다(가격 변경도
  seq를 올리기 때문에 매번 한도가 리셋돼 버린다). 이번 범위(R10~R12)의 시나리오는 "방송 하나에
  드롭 하나"만 다루므로 이 경로가 테스트되지는 않았지만, 실제 운영에서 재고정이 일어나면
  드러날 결함이다 — 고치려면 `LivePin`에 "이 드롭의 식별자"(고정 시각 또는 별도 generation
  카운터, 가격 변경으로는 안 바뀌고 `pin()`으로만 바뀌는 값)를 추가해 그것으로 Redis 키 범위를
  잡아야 한다. 다음 단계(R13~R14 작업) 전에 먼저 다룰 항목으로 남겨 둔다.
