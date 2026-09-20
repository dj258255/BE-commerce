/**
 * 증빙(receipt) 모듈 — 현금영수증·매출전표·세금계산서.
 *
 * <p>결제수단에 따라 법정 증빙을 자동 결정한다: 카드→매출전표, 현금성(가상계좌·이체)→현금영수증,
 * B2B→세금계산서. 현금영수증은 비동기 발급(상태머신)이며, 결제가 취소되면 <b>연쇄 취소</b>된다
 * (결제 취소 이벤트를 구독). payment의 취소 이벤트만 참조한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment" }
)
package com.beomsu.becommerce.receipt;
