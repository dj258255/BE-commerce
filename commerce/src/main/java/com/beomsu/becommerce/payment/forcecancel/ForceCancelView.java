package com.beomsu.becommerce.payment.forcecancel;

/**
 * 운영 어드민에 노출하는 강제취소 요청 뷰(엔티티 직접 노출 대신 필요한 필드만).
 * {@code approvedBy}는 미해결(REQUESTED)이면 null이다.
 */
public record ForceCancelView(Long id, long paymentId, long cancelAmount, String status,
                              String requestedBy, String approvedBy) {

    static ForceCancelView of(ForceCancelRequest req) {
        return new ForceCancelView(req.getId(), req.getPaymentId(), req.getCancelAmount(),
                req.getStatus().name(), req.getRequestedBy(), req.getApprovedBy());
    }
}
