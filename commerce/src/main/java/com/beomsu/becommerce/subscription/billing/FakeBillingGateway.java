package com.beomsu.becommerce.subscription.billing;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 개발/테스트용 가짜 빌링 게이트웨이. 실제 PG 키·네트워크 없이 정기결제 플로우와 dunning 시나리오를
 * 완주한다.
 *
 * <p>기본값은 {@link BillingResult#SUCCESS}이며, 테스트가 {@link #setNextResult}로 다음 청구 결과를
 * 주입해 soft/hard decline 분기를 재현한다.
 */
@Component
@Profile("!prod")
public class FakeBillingGateway implements BillingGateway {

    private final AtomicReference<BillingResult> nextResult =
            new AtomicReference<>(BillingResult.SUCCESS);

    /** 다음 {@link #charge} 호출이 돌려줄 결과를 지정한다. */
    public void setNextResult(BillingResult result) {
        nextResult.set(result);
    }

    public void reset() {
        nextResult.set(BillingResult.SUCCESS);
    }

    @Override
    public BillingResult charge(String billingKey, long amount) {
        return nextResult.get();
    }
}
