# ADR-078. 트랜잭션 격리 수준 — MySQL 기본 REPEATABLE READ 에 둔다

- 상태: 채택 (Accepted)
- 날짜: 2026-10-04
- 관련: [ADR-004](ADR-004-stock-deduction-locking.md), `SettlementItem`, `Order`, `Payment`, `PointAccount`, `WalletAccount`, `Stock`

## 맥락

**격리 수준을 어디에도 명시하지 않았다.** `@Transactional(isolation = …)`·datasource URL·Hikari
설정·compose 의 MySQL 옵션·Flyway 어디에도 격리 수준 설정이 없다. 즉 지금까지 이것은 결정이 아니라
**MySQL 기본값(REPEATABLE READ)에 대한 암묵 의존**이었다. 이 ADR 이 그것을 결정으로 격상한다.

## 결정

**격리 수준을 MySQL 기본(REPEATABLE READ)에 둔다.** 명시 설정을 새로 넣지 않는다.

## 근거

**정합성의 실제 방어는 격리 수준이 아니라 경로별 장치다.**

1. **상태 전이표 + `@Version`** — `Order`·`Payment`·`PointAccount`·`WalletAccount`·`Stock` 등.
   전이표가 불법 전이를 막고, 낙관적 락이 동시 갱신을 직렬화한다.
2. **조건부/원자 UPDATE** — 재고 차감(`WHERE quantity >= :n`), 포인트 적립 upsert
   (`ON DUPLICATE KEY UPDATE balance = balance + :n`).
3. **유니크 제약 + 선검사** — 원장·정산·에스크로, 멱등키(`idempotency_keys`),
   `processed_events(eventKey, consumer)`, `settlement_items.payment_id`.
4. **상태 기반 멱등 가드** — 이미 종결/취소/완료면 no-op.

**격리 수준을 올려도 이 장치들을 대체하지 못하고, 내려도(READ COMMITTED) 이 장치들이 있는 한
정합성 자체는 유지된다.**

**READ COMMITTED 후보는 이미 적혀 있다.** [ADR-004](ADR-004-stock-deduction-locking.md) 는 재고
핫로우의 갭 락 데드락을 피하는 방법으로 `READ COMMITTED`(Shopify 사례)를 후보로 남겼다.
**실측 없이 바꾸지 않는다.**

## 대가

기본값 의존은 환경(RDS 파라미터 그룹 등)이 기본을 바꾸면 **조용히** 달라진다. 그래서 이 ADR 로
명시해 결정임을 못 박고, 전제(운영 DB 의 격리 수준이 REPEATABLE READ 인지)의 확인은 운영
체크리스트 몫으로 남긴다.

## 다시 볼 조건

갭 락 데드락이 실측에서 유의미해질 때. 그때는 ADR-004 가 남긴 READ COMMITTED 후보를 실측으로
비교해 고른다.
