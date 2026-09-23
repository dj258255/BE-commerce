/**
 * 에스크로(escrow) 모듈 — 자금 보류(HELD) 생명주기의 소유자.
 *
 * <p>마켓플레이스/제3자 판매에서 결제 승인 즉시 판매자에게 정산하면, 분쟁·미배송 시 회수가 어렵다.
 * 그래서 결제금을 곧바로 정산 대상으로 흘리지 않고 <b>에스크로에 HELD로 잡아둔다</b>. 구매자가
 * 수령을 확정하면 RELEASED(정산 가능)로, 취소되면 REFUNDED로 전이한다. 일정 기간 무응답이면
 * 자동 구매확정(auto-release)한다.
 *
 * <p>결제 모듈이 발행하는 {@code PaymentConfirmedEvent}/{@code PaymentCanceledEvent}를 구독해
 * 홀드를 만들고 환불하며, payment 모듈을 직접 호출하지 않는다. 구매확정(릴리스)의 <b>소유권 검증은
 * order 모듈</b>이 담당하고(주문이 userId의 소유자이므로), 에스크로는 orderNo만 받아 상태를 전이한다
 * — IDOR 방어의 책임 경계를 order에 두어 에스크로가 인증/소유권을 신경 쓰지 않게 한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment" }
)
package com.beomsu.becommerce.escrow;

import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
