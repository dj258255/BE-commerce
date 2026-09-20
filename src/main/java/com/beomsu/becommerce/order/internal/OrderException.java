package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 주문 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. */
public class OrderException extends DomainException {

    public OrderException(String code, String message) {
        super(code, message);
    }

    /** 금액 위변조 검증 실패 — order가 total_amount의 소유자이므로 이 검증도 order가 담당한다. */
    public static OrderException amountMismatch(long expected, long requested) {
        return new OrderException("AMOUNT_MISMATCH",
                "결제 요청 금액이 주문 금액과 일치하지 않습니다: 주문 %d, 요청 %d".formatted(expected, requested));
    }

    public static OrderException orderNotFound(String orderNo) {
        return new OrderException("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다: " + orderNo);
    }

    public static OrderException productNotFound(long productId) {
        return new OrderException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + productId);
    }

    /** 주문 소유자가 아닌 사용자의 접근 — IDOR 방지. */
    public static OrderException notOwner(String orderNo) {
        return new OrderException("ORDER_FORBIDDEN", "이 주문에 대한 권한이 없습니다: " + orderNo);
    }

    public static OrderException outOfStock(long productId) {
        return new OrderException("OUT_OF_STOCK", "재고가 부족합니다: 상품 " + productId);
    }

    /**
     * 이미 결제가 끝난 주문에 다시 결제하려 할 때.
     *
     * <p>고객이 카드 A 타임아웃 뒤 카드 B 로 눌렀는데, 확인해 보니 A 가 실제로는 승인돼 있던 경우다.
     * 이때 "허용되지 않은 상태 전이입니다"가 나가면 고객은 카드가 거절된 건지 우리가 막는 건지
     * 알 수 없다. 무슨 일이 일어났는지를 그대로 적는다.
     */
    public static OrderException alreadyPaid(String orderNo) {
        return new OrderException("ORDER_ALREADY_PAID",
                "이미 결제가 완료된 주문입니다. 주문 내역에서 확인해 주세요: " + orderNo);
    }

    /**
     * 앞 결제의 결과를 아직 모를 때.
     *
     * <p>재시도 시점에 조회를 걸었는데도 확정이 안 됐다는 뜻이다(조회 실패이거나 PG 가 아직 진행 중).
     * 여기서 새 승인을 내보내면 앞 결제가 실제로 성공했을 때 이중결제가 된다. 기다리게 하되
     * <b>왜 기다려야 하는지</b>는 알려준다.
     */
    public static OrderException paymentResultPending(String orderNo) {
        return new OrderException("PAYMENT_RESULT_PENDING",
                "앞선 결제의 결과를 확인하고 있습니다. 잠시 후 다시 시도해 주세요: " + orderNo);
    }

    public static OrderException invalidTransition(OrderStatus from, OrderStatus to) {
        return new OrderException("INVALID_STATE_TRANSITION",
                "허용되지 않은 상태 전이입니다: %s → %s".formatted(from, to));
    }
}
