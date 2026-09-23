package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.catalog.StockDeductionService;
import com.beomsu.becommerce.order.internal.RefundAllocator.RefundAllocation;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 취소 사가의 <b>트랜잭션 경계</b> — 계획(Phase 1)과 정산(Phase 3)을 각각 짧은 트랜잭션으로 담는다.
 * 그 사이의 카드 취소(Phase 2)는 {@link CancelService}가 트랜잭션 밖에서 payment 모듈에 위임한다.
 *
 * <p>승인 경로의 {@code CheckoutTx}와 같은 구조다. 외부 PG 호출이 트랜잭션 안에 있으면 느린 PG가
 * 주문·포인트·월렛·재고 행을 잠근 채 DB 커넥션을 붙잡아, 폭주 시 풀이 마른다.
 *
 * <p><b>환불을 Phase 3에 모은 이유</b> — 포인트·월렛 환불을 PG 호출 전에 하면, PG가 실패해 재시도할 때
 * 잔여 환불 가능액이 이미 줄어 있어 <b>배분이 달라진다</b>(포인트 몫이 카드 몫으로 밀린다). 계획 단계를
 * 읽기 전용으로 두면 몇 번을 재시도해도 같은 배분이 나오고, PG 멱등키가 같은 금액으로 유지된다.
 */
@Component
@RequiredArgsConstructor
class CancelTx {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final PointService pointService;
    private final WalletService walletService;
    private final StockDeductionService stockDeductionService;

    /** 취소 계획 — Phase 2·3이 쓸 확정 정보. 재시도해도 같은 값이 나오도록 상태를 바꾸지 않고 계산한다. */
    record Plan(long userId, RefundAllocation alloc, boolean fully) {}

    /**
     * Phase 1 — 계획(짧은 tx, 읽기 전용). 소유권·상태·금액을 검증하고 환불 배분을 계산한다.
     * 상태를 바꾸지 않으므로 재시도에 안전하다.
     */
    @Transactional(readOnly = true)
    public Plan plan(String orderNo, long cancelAmount, long authenticatedUserId) {
        if (cancelAmount <= 0) {
            throw new OrderException("INVALID_REQUEST", "취소 금액은 0보다 커야 합니다: " + cancelAmount);
        }
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        // 소유권 검증 — 남의 주문을 취소·환불받는 것(IDOR)을 막는다. 그 무엇보다 먼저.
        order.verifyOwner(authenticatedUserId);

        if (order.getStatus() != OrderStatus.PAID) {
            throw new OrderException("INVALID_STATE_TRANSITION", "결제 완료 주문만 취소할 수 있습니다.");
        }

        // 잔여 결제분 — 이미 환불된 몫을 제외한 포인트·월렛·카드 잔액.
        long paidByPoint = pointService.refundableAmount(orderNo);
        long paidByWallet = walletService.refundableAmount(orderNo);
        long paidByCard = paymentService.cardBalance(orderNo);

        RefundAllocation alloc;
        try {
            alloc = RefundAllocator.allocate(cancelAmount, paidByPoint, paidByWallet, paidByCard);
        } catch (IllegalArgumentException e) {
            throw new OrderException("CANCEL_AMOUNT_EXCEEDED", e.getMessage());
        }
        boolean fully = (cancelAmount == paidByPoint + paidByWallet + paidByCard);
        return new Plan(order.getUserId(), alloc, fully);
    }

    /**
     * Phase 3 — 정산(짧은 tx). 카드 취소가 끝난 뒤 내부 자원을 되돌린다. 포인트·월렛 환불과 적립 회수는
     * orderNo 멱등이라, Phase 2와 3 사이에서 앱이 죽어 재시도로 다시 들어와도 이중 환불되지 않는다.
     */
    @Transactional
    public CancelResult settle(String orderNo, Plan plan) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));
        RefundAllocation alloc = plan.alloc();

        if (alloc.fromPoint() > 0) {
            pointService.refund(plan.userId(), alloc.fromPoint(), orderNo);
        }
        if (alloc.fromWallet() > 0) {
            walletService.refund(plan.userId(), alloc.fromWallet(), orderNo);
        }

        // 적립 회수 — 실결제액(카드+월렛)으로 낸 몫을 되돌렸으니 그 몫의 적립도 회수한다(포인트 파밍 방지).
        // 포인트로 낸 몫은 적립 대상이 아니었으니 회수 대상도 아니다.
        long refundedMoney = alloc.fromWallet() + alloc.fromCard();
        pointService.reverseEarn(plan.userId(), refundedMoney * CheckoutTx.EARN_RATE_PERCENT / 100, orderNo);

        if (plan.fully() && order.getStatus() == OrderStatus.PAID) {
            // 전액 취소: 차감했던 재고를 복원하고 주문을 CANCELED로 전이한다.
            for (OrderItem item : order.getItems()) {
                stockDeductionService.restore(item.getProductId(), item.getQuantity());
            }
            order.cancel();
            // 상태 전이를 saveAndFlush로 명시 영속한다. 이 경로는 조회와 변경 사이에 트랜잭션 경계가
            // 있어 엔티티가 detached일 수 있고, 그러면 dirty check가 아예 동작하지 않는다
            // (ReadOnlyFlushSemanticsTest ④). "readOnly 조회 탓"이라던 옛 설명은 재현으로 반증됐다.
            orderRepository.saveAndFlush(order);
        }
        // 부분취소는 금액 단위라 수량 단위 재고에 매핑되지 않아 복원하지 않는다. 주문은 PAID 유지.

        return new CancelResult(orderNo, order.getStatus(),
                alloc.fromPoint(), alloc.fromWallet(), alloc.fromCard(), plan.fully());
    }
}
