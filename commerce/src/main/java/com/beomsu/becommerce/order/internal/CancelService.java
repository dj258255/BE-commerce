package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 주문 취소 오케스트레이션 애플리케이션 서비스 — order 모듈의 취소 진입점.
 *
 * <p>환불은 <b>포인트 우선</b>으로 배분한다({@link RefundAllocator}). 포인트 몫은 point 모듈에,
 * 카드 몫은 payment 모듈에 위임한다. 카드 취소가 발행하는 {@code PaymentCanceledEvent}를 receipt·
 * ledger가 구독해 현금영수증 연쇄취소·원장 역분개가 자동으로 이어진다.
 *
 * <p>전액 취소일 때만 재고를 복원하고 주문을 CANCELED로 전이한다. 부분취소는 금액 단위라 수량 단위
 * 재고에 매핑되지 않으므로 재고를 복원하지 않고 주문 상태도 PAID로 유지한다.
 *
 * <p><b>취소 사가</b>: {@code cancel}은 계획({@link CancelTx#plan}, tx) → 카드 취소(PG 호출, <b>tx 밖</b>)
 * → 정산({@link CancelTx#settle}, tx) 3단계다. 승인 경로({@code CheckoutService})와 같은 구조로,
 * 느린 PG가 주문·포인트·월렛·재고 행을 잠근 채 DB 커넥션을 붙잡지 않게 한다. 그래서 이 클래스에는
 * 클래스 레벨 {@code @Transactional}을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CancelService {

    private final CancelTx cancelTx;
    private final PaymentService paymentService;

    /**
     * 주문 취소(전액/부분).
     *
     * <p>Phase 1에서 소유권·상태·금액을 검증하고 환불 배분을 확정한다. Phase 2에서 카드 몫만 PG로
     * 취소하고, Phase 3에서 포인트·월렛 환불과 적립 회수·재고 복원을 마친다. Phase 2와 3 사이에서
     * 실패해도 PG 취소와 내부 환불이 모두 멱등이라 재시도로 복구된다.
     */
    public CancelResult cancel(String orderNo, long cancelAmount, String reason, long authenticatedUserId) {
        // Phase 1 (tx) — 검증 + 환불 배분 계산. 상태를 바꾸지 않아 재시도해도 같은 배분이 나온다.
        CancelTx.Plan plan = cancelTx.plan(orderNo, cancelAmount, authenticatedUserId);

        // Phase 2 (tx 밖) — 카드 취소. 이 동안 DB 커넥션 0개 점유.
        if (plan.alloc().fromCard() > 0) {
            paymentService.cancelByOrderNo(orderNo, Money.krw(plan.alloc().fromCard()), reason);
        }

        // Phase 3 (tx) — 내부 자원 환불·적립 회수·재고 복원·주문 상태 확정.
        return cancelTx.settle(orderNo, plan);
    }
}
