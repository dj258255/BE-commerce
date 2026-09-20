/**
 * 판매자 — 정산으로 <b>돈을 받는 쪽</b>과 그 판매자를 제재 명단과 대조하는 일.
 *
 * <p><b>왜 이 모듈이 있나</b>: 정산은 누군가에게 한다. 그 누군가가 제재 대상이면 안 된다.
 * 신원을 갖는 것은 범위 확장이 아니다 — 돈을 보내려면 누구에게 보내는지 필연적으로 알고,
 * 사업자등록번호와 계좌 없이 정산할 방법이 없다. 구매자 신원과 성격이 다르다.
 *
 * <p><b>정산이 이 모듈을 부르지 않는다.</b> 대신 이 모듈이 "이 판매자에게 돈을 내보내도 되는가"를
 * {@link com.beomsu.becommerce.seller.SellerPayoutGate} 로 공개한다. 정산에서 판매자를 직접 읽게 하면
 * 정산이 심사 규칙까지 알게 되고, 규칙이 바뀔 때마다 두 모듈을 같이 고쳐야 한다.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "seller",
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.seller;
