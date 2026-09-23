/**
 * 구독(subscription) 모듈 — 빌링키 기반 정기결제 + dunning + proration.
 *
 * <p>토스페이먼츠는 스케줄링을 제공하지 않으므로 결제 주기 배치·실패 회수(dunning)·상태머신은 전부
 * 가맹점 몫이다. 이 모듈은 그 "실패 설계"를 담는다: soft/hard decline 분기, 유예기간(grace) →
 * 정지(hold) → 만료(expire) 상태머신, 플랜 변경 시 일할계산(proration).
 *
 * <p>PG(빌링) 게이트웨이는 {@link com.beomsu.becommerce.subscription.BillingGateway}로 자체 추상화해
 * payment 모듈에 의존하지 않는다 — 구독은 독립적으로 서고, shared(값 객체·예외)만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.subscription;
