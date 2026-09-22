# PAY-041 — provider별 정산·환불 reference 비교

## 목적

라우팅 이후 승인·환불·정산 사건을 하나의 내부 payment로 복원할 수 있는지 확인한다.

## 완료 조건

- provider transaction id, refund id, payout reference를 내부 사건과 연결한다.
- 누락·중복·provider 변경을 `UNMATCHED` 예외로 남긴다.
- 수수료와 환불이 지급 수식에 어떤 항으로 들어가는지 설명한다.

## 예상

- 예상 시간: 1일(합성 파일)
- 결과물: reference 매핑표, 예외 테스트, 대사 문서
- 위험: provider마다 reference의 생명주기와 재사용 규칙이 다르다.

