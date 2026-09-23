package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.escrow.EscrowReleasedEvent;
import com.beomsu.becommerce.escrow.EscrowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구매확정 오케스트레이션 애플리케이션 서비스 — 에스크로 릴리스의 진입점.
 *
 * <p>구매확정(에스크로 HELD→RELEASED)은 <b>구매자 본인만</b> 할 수 있어야 한다(IDOR 방지). 소유권
 * 검증의 기준값(userId ↔ 주문)은 order가 소유하므로, 진입점을 order에 두고 소유권을 검증한 뒤에만
 * escrow에 릴리스를 위임한다. 이 경계 덕분에 escrow 모듈은 인증/소유권을 신경 쓸 필요가 없다
 * — orderNo만 받아 상태를 전이한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PurchaseConfirmationService {

    private final OrderRepository orderRepository;
    private final EscrowService escrowService;

    /**
     * 구매확정. 처리 순서:
     * <ol>
     *   <li>주문 로드</li>
     *   <li><b>소유권 검증</b> — 그 무엇보다 먼저(IDOR 방지). 남의 주문을 구매확정해 판매자에게
     *       조기 정산시키는 것을 막는다.</li>
     *   <li>상태 검증(PAID만 구매확정 가능)</li>
     *   <li>에스크로 릴리스 위임 — HELD→RELEASED 전이 + EscrowReleasedEvent 발행</li>
     * </ol>
     */
    public PurchaseConfirmationResult confirmPurchase(String orderNo, long authenticatedUserId) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        // 소유권 검증 — 남의 주문 구매확정(IDOR)을 막는다. 에스크로 릴리스 이전에.
        order.verifyOwner(authenticatedUserId);

        // 상태 검증 — 결제 완료(PAID) 주문만 구매확정할 수 있다.
        if (order.getStatus() != OrderStatus.PAID) {
            throw new OrderException("INVALID_STATE_TRANSITION", "결제 완료 주문만 구매확정할 수 있습니다.");
        }

        // 에스크로 릴리스 위임 — 소유권이 확인된 뒤에만 호출된다.
        escrowService.release(orderNo);

        return new PurchaseConfirmationResult(orderNo, "구매확정 완료 — 에스크로 정산");
    }
}
