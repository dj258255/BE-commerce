package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.PaymentHistoryView;
import com.beomsu.becommerce.payment.internal.PaymentCancelView;
import com.beomsu.becommerce.payment.internal.Payment;
import java.util.List;

/**
 * 결제 상세 뷰 — {@code GET /api/v1/payments/{paymentId}}의 응답 본문(10-API-스펙 §2).
 *
 * <p>결제 애그리거트({@code Payment})를 그대로 노출하지 않고, 조회에 필요한 값만 담은 읽기 전용
 * record다. 상태 이력(history)과 취소 이력(cancels)을 함께 실어, 202 UNKNOWN으로 응답된 결제의
 * 확정 여부를 클라이언트가 폴링으로 확인할 수 있게 한다.
 */
public record PaymentDetailView(long paymentId, String orderNo, String status,
                                long amount, long balanceAmount,
                                List<PaymentHistoryView> history, List<PaymentCancelView> cancels) {
}
