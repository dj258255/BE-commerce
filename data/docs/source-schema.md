# 원본 스키마의 출처

원본 MySQL 스키마(`compose/init/mysql/01_schema.sql`)는 [BE-commerce](https://github.com/dj258255/BE-commerce) 커밋 `ed3757d`의 `commerce/src/main/resources/db/migration` V1~V72를 따라가며, 데이터 쪽이 읽는 표 9개를 최종 모양으로 합친 것입니다. 열 이름, 타입, 상태 값, 유일 제약, 외래 키, 인덱스는 원본과 같습니다.

## 가져온 표

| 표 | 행 하나의 뜻 | 원본에서 바뀐 마이그레이션 |
|---|---|---|
| `sellers` | 정산을 받는 판매자 | V36, V39, V49(id 1 플랫폼 직판) |
| `products` | 상품 | V1, V53 |
| `orders` | 주문 | V1 |
| `order_items` | 주문 안의 상품 한 줄 | V1 |
| `payments` | 결제 한 건 | V1, V19, V32, V46, V67 |
| `payment_history` | 결제 상태 전이 한 번 | V1 |
| `internal_records` | 승인(seq 0) 또는 취소(seq 1..N) 한 번 | V1, V21, V25 |
| `settlement_items` | 정산 대상 결제 한 건 | V1, V9, V27, V28, V37, V49, V72 |
| `settlements` | 판매자·통화·날짜별 정산 | V1, V11, V30, V37, V40, V49, V52, V65 |

## 뺀 열

| 표 | 뺀 열 | 이유 |
|---|---|---|
| `sellers` | `representative_name`, `payout_account`, `representative_birth_date` | 개인정보. 데이터 실험에 쓰지 않음 |
| `products` | `description`, `image_url`, `featured` 등 화면용 열 | 집계에 쓰지 않음 |
| `payments` | `card_fingerprint`, `recovery_attempts`, `recovery_next_at` | 사기 탐지와 복구 배치의 내부 상태 |
| `settlements` | `payout_hold_reason`, `payout_instruction_reference`, `payout_reconciliation_*`, `payout_report_*` | 지급 대사. 이 프로젝트 범위 밖 |

## 가져오지 않은 표

결제 대행사 웹훅, 멱등 키, 이벤트 발행 기록, 원장(`ledger_*`), 쿠폰·포인트·지갑, 분쟁, 개인화 관련 표는 가져오지 않았습니다. 정산 조정(`settlement_adjustments`)은 6장 이후 필요해지면 추가합니다.

## 부하 생성기가 흉내 내는 것과 아닌 것

`loadgen/loadgen.py`는 상태 값과 전이 순서(주문 생성 → 결제 진행 → 승인 또는 `UNKNOWN` → 복구 배치 확정, 부분 취소, 구매 확정)를 원본과 같게 씁니다. 재고 차감, 쿠폰, 수수료 계산, 정산 생성(`settlements`)은 하지 않습니다. 판매자는 정산 항목을 만들 때 고릅니다. 원본에서 판매자가 정해지는 규칙과 다를 수 있습니다.
