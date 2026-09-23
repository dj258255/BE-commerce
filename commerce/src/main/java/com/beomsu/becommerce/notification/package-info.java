/**
 * 알림(notification) 모듈 — 결제 완료 이벤트의 첫 소비자.
 *
 * <p>payment가 발행하는 도메인 이벤트를 Spring Modulith 이벤트 레지스트리(= Transactional Outbox)로
 * 전달받아 멱등하게 처리한다. 처리 실패는 DLQ로 격리한다. payment의 노출 이벤트만 참조하며,
 * payment를 역으로 호출하지 않는다(순환 없음).
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment" }
)
package com.beomsu.becommerce.notification;
