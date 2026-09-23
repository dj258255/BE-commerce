# ADR-005. 결제 이벤트를 Kafka로 외부화한다 (Spring Modulith `@Externalized`)

- 상태: 채택 (Accepted)
- 날짜: 2026-07-06
- 관련: [ADR-002](ADR-002-outbox-event-publication-registry.md)(Outbox), [docs/03-아키텍처-설계.md](../03-아키텍처-설계.md) §2

## 맥락

결제 이벤트(`PaymentConfirmedEvent`·`PaymentCanceledEvent`)는 지금까지 Spring Modulith의 **인프로세스 이벤트**로만 소비됐다. ledger·settlement·notification·reconciliation·receipt 리스너가 `@ApplicationModuleListener`로 구독하고, JPA `event_publication`(Outbox)이 유실을 막는다. 이 모놀리스 **안**에서는 완결적이지만, 분석/데이터웨어하우스/별도 서비스처럼 **프로세스 밖** 소비자는 이 이벤트를 받을 수 없다.

ADR-002에서 "외부 Kafka로의 발행은 브릿지로 다시 내보낸다(Phase 3)"고 남겨둔 약속을 이행한다. 이벤트 모델을 바꾸지 않고, 서비스 분리로 진화할 여지를 만드는 것이 목표다.

## 결정

선택된 결제 이벤트에 **`@org.springframework.modulith.events.Externalized`**를 달고, `spring-modulith-events-kafka` 브릿지로 Kafka에 발행한다. 도메인 로직·이벤트 페이로드·기존 인프로세스 리스너는 **무수정**이다.

```java
@Externalized("payment.confirmed::#{orderNo}")
public record PaymentConfirmedEvent(String orderNo, Long paymentId, long amount, Instant approvedAt) {}

@Externalized("payment.canceled::#{orderNo}")
public record PaymentCanceledEvent(String orderNo, Long paymentId, long cancelAmount, boolean fullyCanceled) {}
```

형식은 `토픽명::SpEL_라우팅키`다.

## 근거

1. **Outbox + Kafka = at-least-once (유실 없음).** Modulith는 이벤트를 발행 트랜잭션과 **같은 로컬 트랜잭션**으로 `event_publication`에 기록한다(ADR-002). 커밋 후 외부화 리스너가 Kafka로 발행하고, 성공해야 완료로 마킹한다. 발행 실패/앱 다운이면 미완료로 남아 재기동 시 재발행된다(`republish-outstanding-events-on-restart`). "DB 커밋과 Kafka 발행"의 dual-write 문제를 Outbox가 흡수하므로 **at-least-once**가 보장된다 → 프로세스 밖 소비자도 인프로세스 소비자와 똑같이 멱등 컨슈머로 설계한다.
2. **orderNo를 파티션 키로 → 주문 단위 순서 보존.** 라우팅 키 `#{orderNo}`가 Kafka 메시지 키가 되고, 같은 키는 같은 파티션으로 간다. Kafka는 파티션 내 순서만 보장하므로, 같은 주문의 `confirmed → canceled`가 **역전 없이** 순서대로 도착한다. (전역 순서가 아니라 주문 단위 순서만 필요하다. Zero-Payload 이벤트라 소비자가 최신 상태를 조회로 확정할 수도 있다.)
3. **바퀴를 다시 발명하지 않는다.** 브릿지·직렬화·라우팅을 프레임워크가 검증된 형태로 제공한다.

## 브로커 부재 안전장치 (프로퍼티 게이트)

브로커가 없어도 앱이 떠야 한다(로컬 개발·테스트·CI). Modulith의 외부화 자동설정은 `spring.modulith.events.externalization.enabled`(기본 true)로 켜지므로, 이를 **기본 `false`로 내려** 외부화 리스너 자체를 비활성화한다.

- **기본(게이트 off):** `@Externalized` 애노테이션은 남지만 외부화 리스너가 등록되지 않아 Kafka로 아무것도 나가지 않는다. Outbox 기반 **인프로세스 소비는 그대로** 동작하고, 브로커 없이 부팅·테스트가 100% 통과한다.
- **운영/데모(게이트 on):** `kafka` 프로파일(`SPRING_PROFILES_ACTIVE=kafka`)이 `externalization.enabled=true` + producer를 켠다. producer는 `acks=all`(유실 방지)과 멱등 producer(`enable.idempotence=true`, 재시도 중복 억제)로 구성한다.

## 포기한 것 / 주의

- **전역 순서는 보장하지 않는다.** 주문 단위 순서만 보존한다(파티션 키=orderNo). 서로 다른 주문 간 순서는 소비자가 의존하면 안 된다.
- at-least-once이므로 **중복 발행 가능** → 소비자는 반드시 멱등. (ADR-002의 processed_events 결과 소비 원칙과 동일.)
- 어떤 이벤트를 외부화할지는 애노테이션으로 **명시적으로 선택**한다. 모든 도메인 이벤트를 무분별하게 내보내지 않는다(스키마 결합 최소화).

## 실증

- 프로세스 밖 소비자 실증: 별도 프로세스 앱 [`consumer-app/`](../../commerce/consumer-app/README.md)(독립 Gradle 프로젝트)이 `payment.confirmed`/`payment.canceled`를 구독한다. 도메인 코드는 무수정이다.
