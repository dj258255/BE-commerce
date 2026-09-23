package com.beomsu.becommerce.payment;

import org.springframework.modulith.events.Externalized;

/**
 * 결제 취소(전액/부분) 이벤트. ledger가 역분개를, settlement가 정산액 반영을 위해 구독한다.
 *
 * <p>승인 이벤트와 마찬가지로 {@code @Externalized}로 Kafka에 외부화하며, 라우팅 키를
 * {@code orderNo}로 잡아 같은 주문의 승인/취소 이벤트가 같은 파티션에서 순서대로 흐르게 한다.
 *
 * <p>{@code cancelAmount}는 이번 취소분(델타)이고, {@code settleableBalance}는 <b>취소 후 남은
 * 정산 가능 잔액(절대값)</b>이다. 정산은 델타를 빼는 대신 이 절대 잔액으로 항목 금액을 세팅해,
 * at-least-once 재배달에도 이중 차감되지 않게 한다(멱등). 델타는 원장 역분개·에스크로 환불이 쓴다.
 *
 * <p>{@code cancelSeq}는 이 결제의 몇 번째 취소인가다. 한 결제는 여러 번 취소될 수 있어서
 * 원결제 식별자만으로는 취소 건을 구분할 수 없다. 원장 역분개가 이 순번으로 중복을 판정한다.
 *
 * @param cancelSeq 이 결제의 취소 순번(1부터)
 * @param settleableBalance 이 취소가 반영된 뒤의 취소 가능 잔액(=정산 대상 금액). 전액취소면 0.
 */
@Externalized("payment.canceled::#{orderNo}")
public record PaymentCanceledEvent(String orderNo, Long paymentId, int cancelSeq, long cancelAmount,
                                   long settleableBalance, boolean fullyCanceled,
                                   java.time.Instant canceledAt) {
}
