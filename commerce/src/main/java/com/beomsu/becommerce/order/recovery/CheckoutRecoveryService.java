package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.CheckoutTx;
import com.beomsu.becommerce.payment.StuckPaymentInfo;
import com.beomsu.becommerce.payment.ApprovalOutcome;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.StuckPaymentInfo;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 멈춘 체크아웃 사가 복구 — ADR-007 사가 이행의 "진짜 어려운 부분".
 *
 * <p>사가는 원자성을 포기한 대가로 새 크래시 엣지케이스가 생긴다: 예약(Phase 1)이 커밋된 뒤 확정(Phase 3)
 * 전에 앱이 죽으면, 주문은 {@code PAYMENT_IN_PROGRESS}로, 결제는 IN_PROGRESS로, 포인트는 예약된 채 멈춘다.
 * 이 서비스가 그 멈춘 주문을 스캔해, 카드 결제를 PG 조회로 확정한 뒤 {@link CheckoutTx#settle}을 재실행해
 * 주문을 완결(승인됐으면 재고차감·PAID)하거나 롤백(실패면 포인트 복원·PENDING 복귀)한다.
 *
 * <p>재진입 안전: {@code settle}은 {@code applyResult}가 멱등(IN_PROGRESS일 때만 전이)이라 여러 번 돌아도
 * 안전하다. 카드 결제가 없는 전액 포인트 주문은 PG 조회 없이 완결한다(포인트가 곧 결제).
 */
@Service
@RequiredArgsConstructor
public class CheckoutRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutRecoveryService.class);

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final WalletService walletService;
    private final CheckoutTx checkoutTx;

    /** 이 시간 이상 PAYMENT_IN_PROGRESS로 머문 주문만 복구 대상 — 진행 중인 정상 체크아웃과 겹치지 않게. */
    @Value("${app.checkout.recovery.stuck-after-minutes:10}")
    private long stuckAfterMinutes;

    /**
     * 멈춘 체크아웃을 스캔해 완결/롤백한다. 반환값은 처리한 건수.
     *
     * <p><b>영영 못 고치는 건이 큐를 막지 않게</b> 오래된 순으로 고르고, 실패한 건은 시도
     * 시각을 남겨 뒤로 보낸다. 계속 실패하는 건은 여기서 조용히 사라지지 않고
     * <b>"미확정 결제가 가장 오래 방치된 시간"</b> 지표에서 계속 늙는다. 막힌 것을 아는 일은
     * 그 지표가 하고, 이 배치는 다른 건의 차례를 지킨다.
     */
    public int recoverStuckCheckouts() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(stuckAfterMinutes));
        List<Order> stuck = orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                OrderStatus.PAYMENT_IN_PROGRESS, threshold, chunk());

        int recovered = 0;
        for (Order order : stuck) {
            try {
                resolveNow(order);
                recovered++;
            } catch (Exception e) {
                // 한 건 실패가 배치를 멈추지 않게 격리한다. 그리고 <시도했다는 사실을 남긴다>.
                // 이걸 안 남기면 그 건이 다음 회차에도 같은 앞자리를 잡는다. 상한이 있는 조회라
                // 앞의 100건이 계속 실패하면 101번째는 영영 차례가 안 온다. 남기면 임계 시간을
                // 다시 채워야 해서 유예가 생기고, 오래된 순 정렬에서 뒤로 밀린다.
                markAttempted(order);   // CheckoutTx 를 통해 별도 트랜잭션으로 남긴다
                log.warn("멈춘 체크아웃 복구 실패 orderNo={} : {}", order.getOrderNo(), e.getMessage());
            }
        }
        if (recovered > 0) {
            log.info("멈춘 체크아웃 복구 완료 recovered={}", recovered);
        }
        return recovered;
    }

    /**
     * 시도했다는 사실만 남긴다. <b>이것 때문에 배치를 멈추지 않는다</b> — 기록에 실패해도
     * 삼키고 다음 건으로 간다. 다음 건의 차례를 지키자고 시작한 일이다.
     */
    private void markAttempted(Order order) {
        try {
            checkoutTx.markRecoveryAttempted(order.getId());
        } catch (Exception e) {
            log.warn("복구 시도 기록 실패 orderNo={} : {}", order.getOrderNo(), e.getMessage());
        }
    }

    /**
     * 미확정 체크아웃 한 건을 <b>지금</b> 해소한다. PG 조회로 확정하고 그 결과를 주문에 반영한다.
     *
     * <p>배치가 스캔해서 부르고, <b>고객이 다른 수단으로 재시도할 때도 부른다</b>. 고객은 이미 화면
     * 앞에 있는데 배치 주기를 기다리게 할 이유가 없다. 조회 한 번이면 승인이었는지 아닌지 갈린다.
     *
     * <p>이 메서드를 안 부르고 재시도를 허용하면, 앞 결제가 실제로 승인돼 있었을 때 <b>이중결제</b>가
     * 된다. 멱등키는 이걸 못 막는다 — 카드를 바꾼 재시도는 다른 요청이라 새 키를 받는다.
     */
    public void resolveNow(Order order) {
        // 카드 결제를 PG 조회로 확정(없으면 전액 포인트 → empty).
        Optional<StuckPaymentInfo> info = paymentService.resolveStuckPayment(order.getOrderNo());
        long cardAmount = info.map(StuckPaymentInfo::amount).orElse(0L);
        // 월렛 예약분은 append-only 월렛 원장(orderNo 키)에서 역산한다 — 원 요청의 결제수단 분할이
        // 주문에 저장돼 있지 않으므로, 커밋된 USE 이력이 진실의 원천이다.
        long walletAmount = walletService.reservedAmountForOrder(order.getOrderNo());
        // 포인트분은 금액 검증 불변식(카드+포인트+월렛=총액)에서 도출한다.
        long pointAmount = order.getTotalAmount() - cardAmount - walletAmount;
        ApprovalOutcome outcome = info.map(StuckPaymentInfo::outcome).orElse(null);
        Long paymentId = info.map(StuckPaymentInfo::paymentId).orElse(null);

        checkoutTx.settle(order.getOrderNo(), paymentId, Money.krw(cardAmount),
                pointAmount, walletAmount, outcome);
    }

    /**
     * 배치 한 번이 읽는 상한. 남은 것은 다음 주기가 가져간다.
     *
     * <p><b>필드에 기본값을 둔다.</b> {@code @Value} 는 스프링이 만들어 줄 때만 채워지는데,
     * 단위 테스트는 이 서비스를 직접 생성한다. 초기값이 없으면 0 이 되어 페이지 크기가
     * 0 이라고 터진다 — 실제로 그렇게 깨졌다.
     */
    @org.springframework.beans.factory.annotation.Value("${app.batch.read-chunk-size:500}")
    private int readChunkSize = 500;

    /**
     * <b>설정이 0 이나 음수여도 배치를 죽이지 않는다.</b> 잘못된 설정 하나로 돈을 다루는
     * 배치가 멈추는 것보다, 기본값으로 도는 편이 낫다.
     */
    private org.springframework.data.domain.Pageable chunk() {
        return org.springframework.data.domain.PageRequest.of(0, readChunkSize > 0 ? readChunkSize : 500);
    }
}
