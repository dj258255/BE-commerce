package com.beomsu.becommerce.subscription.billing;

/**
 * 빌링(정기결제) 게이트웨이 추상화.
 *
 * <p>토스페이먼츠의 {@code POST /v1/billing/{billingKey}}(무인증 자동결제)를 흉내 낸 자체 인터페이스.
 * 이 추상화 덕분에 subscription 모듈은 payment 모듈이나 특정 PG 구현에 의존하지 않는다. 실제 연동은
 * 어댑터로 갈아끼우고, 테스트/개발에서는 {@link FakeBillingGateway}를 쓴다.
 */
public interface BillingGateway {

    /**
     * 빌링키로 지정 금액을 청구한다.
     *
     * @param billingKey 카드정보를 토큰화한 빌링키
     * @param amount     청구 금액(원)
     * @return soft/hard decline을 구분한 청구 결과
     */
    BillingResult charge(String billingKey, long amount);
}
