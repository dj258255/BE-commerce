# PAY-039 — tentative/posted 대사 모델 검토

## 목적

사람 확인이 필요한 `PENDING`과 외부 금융 거래의 provisional/pending을 분리할지 결정한다.

## 완료 조건

- pending 파일과 posted 파일을 같은 지급 reference로 연결하는 합성 시나리오가 있다.
- pending 결과만으로 지급·원장 확정을 하지 않는다.
- posted 도착 후 최종 대사를 다시 실행하고 잠정 결과를 감사 이력으로 보존한다.

## 예상

- 예상 시간: 1일
- 결과물: 상태 전이 ADR, 합성 파일, 재대사 테스트
- 위험: 실제 PG·은행이 provisional/posted 상태와 동일 reference를 제공해야 한다.

