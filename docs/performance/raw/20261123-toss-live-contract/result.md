# 토스 테스트 환경 실계약 테스트 실행 기록

- 실행: 2026-11-23 09:27 KST, 로컬 맥, 키는 ~/.toss-test-key(test_sk_ 테스트 키, 커밋하지 않음)
- 명령: ./gradlew -p commerce integrationTest --tests '*TossPgLiveContractTest'
- 대상: https://api.tosspayments.com (테스트 키라 실제 출금 없음)
- 결과: tests="3" skipped="0" failures="0" errors="0"

## 출력

```
[승인] 없는 결제 키 → outcome=FAILED reason=NOT_FOUND_PAYMENT_SESSION: 결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.
[취소] 없는 결제 키 → 취소 재시도 무의미(NOT_FOUND_PAYMENT): 존재하지 않는 결제 정보 입니다.
[조회] 없는 결제 키 → MISSING
```

## 덮지 않은 것

- 실제 결제 한 건의 승인 · 조회 · 취소 · 재취소(TossPgLiveLifecycleTest)는 카드 정보 파일(~/.toss-test-card)이 없어 건너뜀
- 결제창 인증을 마친 뒤 승인 전 상태와 승인 타임아웃 뒤 상태의 조회 응답은 결제창을 거쳐야 해서 재지 않음. 위 조회는 존재하지 않는 결제 키의 404 다
