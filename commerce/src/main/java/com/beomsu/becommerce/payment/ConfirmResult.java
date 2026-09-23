package com.beomsu.becommerce.payment;

/** 승인 결과. order/컨트롤러가 후속 처리(주문 확정/재고 차감)를 판단하는 데 쓴다. */
public record ConfirmResult(Long paymentId, PaymentStatus status, String method, String message) {

    public boolean isApproved() {
        return status == PaymentStatus.DONE;
    }

    public boolean isUnknown() {
        return status == PaymentStatus.UNKNOWN;
    }
}
