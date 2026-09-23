package com.beomsu.becommerce.order.internal;

/**
 * 부분취소 환불 배분기 — 취소 금액을 결제 수단(포인트/월렛/카드)에 배분한다.
 *
 * <p><b>내부 재원(포인트→월렛)을 카드보다 먼저 환불한다.</b> 카드부터 환불하면 무상 지급된 포인트나
 * 선불 충전분을 현금화하는 어뷰징이 가능하기 때문이다(예: 10,000원 = 포인트 3,000 + 카드 7,000 결제 후
 * 7,000원을 카드로 환불받으면 포인트 3,000이 사실상 현금이 된다). 포인트·월렛 복원은 내부라 즉시·확실하고,
 * 카드 부분취소는 카드사 의존이며 회계상 역분개도 최소화된다. 포인트를 월렛보다 먼저 두는 건, 포인트가
 * 적립·프로모션으로 무상 지급될 수 있어 현금화 어뷰징 유인이 더 크기 때문이다.
 */
public final class RefundAllocator {

    private RefundAllocator() {
    }

    /**
     * 취소 금액을 포인트 → 월렛 → 카드 순으로 배분한다.
     * 불변식: {@code fromPoint + fromWallet + fromCard == cancelAmount}, 각 배분액은 해당 결제분을 넘지 않는다.
     *
     * @param cancelAmount 이번에 취소할 금액
     * @param paidByPoint  이 주문에서 포인트로 결제한(아직 환불되지 않은) 금액
     * @param paidByWallet 이 주문에서 월렛으로 결제한(아직 환불되지 않은) 금액
     * @param paidByCard   이 주문에서 카드로 결제한(아직 환불되지 않은) 금액
     */
    public static RefundAllocation allocate(long cancelAmount, long paidByPoint, long paidByWallet, long paidByCard) {
        if (cancelAmount < 0 || paidByPoint < 0 || paidByWallet < 0 || paidByCard < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다.");
        }
        long totalRefundable = paidByPoint + paidByWallet + paidByCard;
        if (cancelAmount > totalRefundable) {
            throw new IllegalArgumentException(
                    "취소 금액이 잔여 결제 금액을 초과합니다: 취소 %d, 잔여 %d"
                            .formatted(cancelAmount, totalRefundable));
        }
        long fromPoint = Math.min(cancelAmount, paidByPoint);           // 포인트 먼저
        long remaining = cancelAmount - fromPoint;
        long fromWallet = Math.min(remaining, paidByWallet);            // 그다음 월렛
        long fromCard = remaining - fromWallet;                         // 나머지를 카드에서
        return new RefundAllocation(fromPoint, fromWallet, fromCard);
    }

    /** 환불 배분 결과. */
    public record RefundAllocation(long fromPoint, long fromWallet, long fromCard) {
    }
}
