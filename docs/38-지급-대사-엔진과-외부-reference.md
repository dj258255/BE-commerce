# 지급 대사 엔진과 외부 reference

## 문제

정산을 만들었다는 사실과 실제로 돈이 지급됐다는 사실은 다르다. 내부 정산의 `netAmount`가
97,300원이라고 해서 외부 PG·은행에서 97,300원이 최종 게시됐다고 확정할 수는 없다.

특히 다음 입력은 서로 다른 상태다.

- 외부 report가 아직 `pending`이다.
- 외부 행에 내부 정산과 연결할 reference가 없다.
- 같은 reference가 두 번 왔다.
- 금액 또는 통화가 다르다.
- reference·통화·posted 금액이 모두 같다.

금액만 비교해 `PAID_OUT`으로 바꾸면 다른 판매자의 지급을 잘못 연결하거나, 잠정 지급을 최종 지급으로
오인할 수 있다.

## 결정

`PayoutReconciliationEngine`은 외부 report를 정규화된 입력으로 받고 다음 순서로 판정한다.

1. 내부 정산 기대치의 `payoutReference`가 중복이면 입력을 거부한다.
2. 외부 reference가 없으면 `UNMATCHED_PAYOUT`으로 남긴다.
3. 외부 reference가 중복이면 금액이 같아도 `DUPLICATE_PAYOUT_REFERENCE`로 남긴다.
4. reference가 연결돼도 외부 행이 아직 posted가 아니면 `PENDING`이다.
5. posted 이후 통화가 다르면 환율을 추측하지 않고 `CURRENCY_MISMATCH`다.
6. 통화가 같고 금액이 다르면 signed difference를 포함한 `AMOUNT_MISMATCH`다.
7. reference·통화·posted 금액이 모두 같을 때만 `MATCHED`다.

이 엔진은 아직 은행이나 PG를 호출하지 않는다. 대신 실제 계약이 생겼을 때 필요한 입력 계약과 확정
경계를 먼저 고정한다. 현재 어드민 지급 경로도 이 엔진을 거쳐 `MATCHED`일 때만 `PAID_OUT`을
만들며, 실제 지급 reference가 없는 환경에서 임의로 확정하지 않는다.

## 구현과 검증

- 구현: `src/main/java/com/beomsu/becommerce/settlement/internal/PayoutReconciliationEngine.java`
- 상태: `PayoutReconciliationStatus`
- 검증: `PayoutReconciliationEngineTest`
- 확인한 경우: exact match, pending, signed amount mismatch, reference 누락·중복, 양쪽 누락,
  currency mismatch

실제 PG·은행 report를 붙일 때는 다음을 추가로 확인해야 한다.

- 지급 reference가 정산 생성 시점부터 최종 bank reference까지 동일하게 유지되는가
- pending과 posted가 같은 reference로 갱신되는가
- 수수료·reserve·환불·chargeback이 어느 report 행과 어떤 부호로 표현되는가
- 동일 파일 재수집 시 report 행의 식별자가 안정적인가
- 지급을 먼저 `PAID_OUT`으로 기록할지, posted 확인 후에만 전이할지 계약상 허용되는가

## 판단의 비용

보수적인 판정은 지급 확정을 늦춘다. 반대로 금액만으로 확정하면 운영 화면은 빨라지지만 잘못된
지급 확정과 회계 추적 단절을 되돌리는 비용이 커진다. 현재 pay는 실제 외부 계약이 없는 상태이므로
후자를 선택하지 않고, `PENDING`을 지급 확정으로 승격하지 않는 쪽을 택했다. 기존 수동 확정
경로도 `V65`의 지급 지시 reference와 대사 결과를 기록하도록 바뀌었다.

## 참고

- [Modern Treasury - Tentative Reconciliation](https://www.moderntreasury.com/journal/tentative-reconciliation)
- [Modern Treasury - Reconciliation Is a Knapsack Problem](https://www.moderntreasury.com/journal/reconciliation-is-a-knapsack-problem)
- [Adyen - Financial Reconciliation](https://www.adyen.com/knowledge-hub/financial-reconciliation)
- [Stripe - Ledger](https://stripe.com/blog/ledger-stripe-system-for-tracking-and-validating-money-movement)
