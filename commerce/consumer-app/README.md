# BE-commerce-consumer

결제 이벤트를 프로세스 밖에서 소비하는 정산 알림 데모입니다.

메인 BE-commerce 앱이 `@Externalized`로 Kafka에 내보내는 `payment.confirmed` / `payment.canceled`
토픽을 구독하는 **별도 프로세스** 앱이다. 도메인 코드를 한 줄도 고치지 않고 다른 프로세스가
결제 이벤트를 받는 것을 실증한다. [ADR-005](../../docs/adr/ADR-005-event-externalization-kafka.md)의
"프로세스 밖 소비자" 약속을 이행한 것이다.

> 메인 빌드에 영향을 주지 않도록 **독립 Gradle 프로젝트**로 둔다
> (루트 settings.gradle에 include하지 않아 메인 CI/테스트 무영향).

## 실행

```bash
docker compose up -d kafka mysql redis
SPRING_PROFILES_ACTIVE=kafka ./gradlew -p commerce bootRun            # 메인 앱(외부화 on)
./gradlew -p commerce/consumer-app bootRun                          # 소비자 앱(별도 프로세스)
```

결제 승인/취소가 일어나면 소비자 콘솔에 `[정산알림] 결제 완료 수신 orderNo=... amount=...`
로그가 찍힌다.

## 설계 메모

- **String + Jackson 역직렬화**: producer의 JsonSerializer 타입 헤더(메인 앱 클래스명)에
  결합되지 않도록 값을 String으로 받아 `readTree`로 파싱한다.
- **at-least-once**: Outbox 재발행으로 중복 수신이 가능하다. 실소비자는 멱등 처리가 필수다(여기선 로그 데모).
- **Zero-Payload**: 페이로드는 식별자+최소 정보. 상세는 orderNo로 조회 API를 되읽어 확정한다.
