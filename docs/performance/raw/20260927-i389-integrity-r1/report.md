# 정합성 불변식 점검(#389)

## AT_PAYMENT

점검 호출 415번, 실패 0번.

| 유예(초) | 부하 중 최대 위반 합 | 부하 뒤 최대 위반 합 | 위반이 나온 점검 수 / 전체 | 나온 불변식 |
|---:|---:|---:|---|---|
| 0 | 0 | 0 | 0 / 69 | - |
| 5 | 0 | 0 | 0 / 69 | - |
| 15 | 0 | 0 | 0 / 69 | - |
| 30 | 0 | 0 | 0 / 69 | - |
| 60 | 0 | 0 | 0 / 69 | - |
| 120 | 0 | 0 | 0 / 69 | - |

마지막 점검(유예 0): 위반 합 0 

## CHECK

점검 호출 415번, 실패 0번.

| 유예(초) | 부하 중 최대 위반 합 | 부하 뒤 최대 위반 합 | 위반이 나온 점검 수 / 전체 | 나온 불변식 |
|---:|---:|---:|---|---|
| 0 | 0 | 0 | 0 / 69 | - |
| 5 | 0 | 0 | 0 / 69 | - |
| 15 | 0 | 0 | 0 / 69 | - |
| 30 | 0 | 0 | 0 / 69 | - |
| 60 | 0 | 0 | 0 / 69 | - |
| 120 | 0 | 0 | 0 / 69 | - |

마지막 점검(유예 0): 위반 합 0 

## 일부러 어긋낸 데이터

배치를 끄고 다시 띄운 뒤 오염 전 위반 합: 0

| 넣은 오염 | 늘어난 불변식 | 의도한 불변식만 1 늘었나 |
|---|---|---|
| PAYMENT_DONE_ORDER_NOT_PAID | {"PAYMENT_DONE_ORDER_NOT_PAID": 1} | 예 |
| ORDER_PAID_PAYMENT_NOT_DONE | {"ORDER_PAID_PAYMENT_NOT_DONE": 1} | 예 |
| APPROVED_WITHOUT_LEDGER | {"APPROVED_WITHOUT_LEDGER": 1} | 예 |
| LEDGER_AMOUNT_MISMATCH | {"LEDGER_AMOUNT_MISMATCH": 1} | 예 |
| LEDGER_UNBALANCED | {"LEDGER_UNBALANCED": 1} | 예 |
| APPROVED_WITHOUT_RECON_RECORD | {"APPROVED_WITHOUT_RECON_RECORD": 1} | 예 |
| FAILED_ORDER_CARD_NOT_REFUNDED | {"FAILED_ORDER_CARD_NOT_REFUNDED": 1} | 예 |
| RESERVATION_LEFT_OPEN | {"RESERVATION_LEFT_OPEN": 1} | 예 |
| STOCK_NEGATIVE | {"STOCK_NEGATIVE": 1} | 예 |
| UNKNOWN_OVER_10_MINUTES | {"UNKNOWN_OVER_10_MINUTES": 1} | 예 |
