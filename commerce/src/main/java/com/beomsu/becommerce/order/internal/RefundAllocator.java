package com.beomsu.becommerce.order.internal;

/**
 * 부분취소 환불 배분기 — 취소 금액을 결제 수단(포인트/월렛/카드)에 배분한다.
 *
 * <p><b>내부 재원(포인트→월렛)을 카드보다 먼저 환불한다(ADR-087).</b> 근거는 둘이다.
 * ① 내부 재원 안에서 끝나는 취소는 PG 를 부르지 않는다({@code CancelService} 는 카드 몫이 0이면 호출하지 않음).
 * PG 부분취소는 타임아웃이면 결과를 모르는 상태가 되지만 포인트·월렛 복원은 우리 DB 한 트랜잭션에서 끝난다.
 * ② 포인트에 사용 조건(예: 1만 원 이상 주문)이 생기면 카드부터 돌려줄 때 남은 주문이 조건을 못 채운 채 포인트로만
 * 남는다. 포인트를 월렛보다 앞에 두는 건 포인트가 무상 지급될 수 있어 조건 우회 유인이 더 크기 때문이다.
 * 예전 주석의 "카드부터 환불하면 포인트가 현금이 된다"는 지금 규칙(사용 조건·만료 없음, 실결제액 적립은 취소분만큼 회수)으로는 성립하지 않아 고쳤다.
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
