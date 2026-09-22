# 대사 결과가 맞아도 확정하면 안 되는 경우: Pending과 Posted의 경계

## 문제

현재 pay의 `PENDING`은 내부 기록과 외부 기록이 맞지 않아 사람이 확인해야 하는 예외 상태다.
하지만 외부 금융 데이터의 `pending`은 아직 최종 게시되지 않은 거래라는 뜻일 수 있다. 두 의미를
같은 상태로 합치면 “현재 보이는 값이 맞다”와 “최종적으로 확정됐다”를 구분할 수 없게 된다.

Modern Treasury는 pending 거래를 잠정 대사할 수 있지만, 이후 posted 거래가 오면 기존 잠정 기록을
보관하고 posted 기록으로 다시 대사한다. 잠정 대사는 운영자가 빨리 볼 수 있다는 장점이 있지만,
최종 확정으로 취급하면 취소·실패·금액 변경을 놓칠 수 있다.

참고: [Modern Treasury - Tentative Reconciliation](https://www.moderntreasury.com/journal/tentative-reconciliation)

## 선택지

| 선택지 | 얻는 것 | 잃는 것 | pay의 판단 |
|---|---|---|---|
| `PENDING` 하나로 통합 | 모델·화면 단순성 | 사람 예외와 외부 미게시를 혼동 | 채택하지 않음 |
| `TENTATIVE`와 `EXCEPTION` 분리 | 운영자가 빨리 보고 최종 확정도 구분 | 상태·전이·재대사 복잡성 | 다음 단계 후보 |
| 최종 파일만 대사 | 오판 위험 최소화 | 발견·대응이 늦어짐 | 파일 계약이 배치라면 현실적 |

## 현재 구현과 경계

- 현재 `PENDING`은 사람 확인이 필요한 불일치 큐다.
- `AUTO_RESOLVED`는 같은 거래일·주문번호·금액이 결정적으로 맞은 경우다.
- 웹훅의 `PENDING_PAYMENT`은 결제 행보다 이벤트가 먼저 온 순서 역전 상태다.
- 외부 PG가 provisional/posted를 구분해 주는 계약은 아직 없다.

따라서 지금은 `PENDING`을 곧 “외부 금융 거래가 잠정 상태”라고 설명하지 않는다. 실제 PG·은행
파일에 게시 상태가 들어오면 `recon_evidence_state`와 `finalized_at`을 별도 도입하고, 잠정 대사와
사람 예외 큐를 분리한다.

## 완료 조건

- 동일한 지급 reference가 pending 파일과 posted 파일에 나타났을 때 두 레코드를 연결한다.
- pending에서 자동 지급하지 않는다.
- posted가 도착하면 잠정 판정은 보관하고 최종 판정만 지급·원장 확정의 근거로 사용한다.
- pending→posted 지연 시간과 posted 이후 재대사 결과를 측정한다.

---

## 관련 카드

- PAY-039 tentative/posted 대사 모델 검토
- PAY-035 지급 reconciliation

