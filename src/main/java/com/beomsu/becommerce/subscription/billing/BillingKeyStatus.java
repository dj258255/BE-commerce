package com.beomsu.becommerce.subscription.billing;

/**
 * 빌링키를 아직 쓸 수 있는가.
 *
 * <p>둘뿐이다. "만료"를 따로 두지 않는 이유는 <b>우리가 만료를 모르기 때문</b>이다.
 * 카드 유효기간은 카드사가 알고, 우리는 결제가 실패한 뒤에야 안다. 모르는 것을 상태로
 * 만들면 그 상태가 언제 참인지 아무도 답할 수 없다.
 */
public enum BillingKeyStatus {

    /** 쓸 수 있다. */
    ACTIVE,

    /** 더는 못 쓴다. <b>사유는 따로 남긴다</b> — 카드가 죽은 것과 고객이 해지한 것은 대응이 다르다. */
    REVOKED
}
