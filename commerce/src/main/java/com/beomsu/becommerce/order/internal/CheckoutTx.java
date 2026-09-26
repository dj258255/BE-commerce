package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.compensation.CompensationService;
import com.beomsu.becommerce.order.catalog.StockDeductionService;
import com.beomsu.becommerce.order.catalog.StockReservationService;
import com.beomsu.becommerce.payment.ApprovalOutcome;
import com.beomsu.becommerce.payment.ConfirmResult;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 체크아웃 사가(ADR-007)의 <b>트랜잭션 경계</b> — 예약(Phase 1)과 확정(Phase 3)을 각각 짧은 트랜잭션으로
 * 담는다. 그 사이의 PG 승인(Phase 2)은 {@link CheckoutService}가 트랜잭션 밖에서 호출한다.
 *
 * <p>별도 빈으로 둔 이유: {@code CheckoutService.confirm}(오케스트레이터, 비트랜잭션)이 이 메서드들을
 * 프록시로 호출해야 각 단계의 {@code @Transactional}이 실제 트랜잭션 경계가 된다(자기호출이면 우회됨).
 */
@Component
@RequiredArgsConstructor
// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class CheckoutTx {

    /** 적립률(정책) — 실결제액(카드+월렛)의 %. 취소 시 적립 회수도 이 율을 쓴다. 쿠폰/등급 차등은 이후 확장. */
    static final long EARN_RATE_PERCENT = 1;

    private final PaymentService paymentService;
    private final OrderRepository orderRepository;
    private final StockDeductionService stockDeductionService;
    private final PointService pointService;
    private final WalletService walletService;
    private final CompensationService compensationService;
    private final StockReservationService stockReservationService;

    /** 예약 결과 — 확정 단계로 넘길 최소 정보. cardAmount==0(전액 포인트)이면 paymentId는 null. */
    record Reservation(Long paymentId) {}

    /**
     * Phase 1 — 예약(짧은 tx). 검증 → 주문 상태 전이(이중지불 잠금) → 포인트 선점 → 결제 IN_PROGRESS 적재
     * → 월렛 차감. PG 콜 <b>전</b>에 커밋되어 커넥션을 반납한다. 크래시 시에도 이 상태(주문 IN_PROGRESS +
     * 결제 IN_PROGRESS + 포인트/월렛 예약)가 남아 복구 배치가 완결/롤백할 수 있다.
     *
     * <p><b>결제수단별 롤백 계약</b>: 포인트({@link PointService})와 월렛({@link WalletService#use})의 저장소 호출은
     * 모두 이 tx에 합류한다. 그래서 예약 단계 어디서 실패해도 주문 전이 · 결제 행 · 포인트 · 월렛이 함께 롤백된다
     * (#375 에서 실 MySQL 로 확인. 그 전에는 7인자 {@code reserve} 에 {@code @Transactional} 이 없어 각각 커밋됐다).
     * 월렛 차감을 <b>맨 마지막</b>에 두는 이유는 잔액 부족이 가장 흔한 실패라 앞의 일을 적게 하고 끝내기 위해서다.
     * 대가: 월렛의 낙관적 락 재시도는 이 tx 안에서는 첫 충돌에 rollback-only 가 되어 소용없다(같은 사용자의
     * 동시 결제에서만 생긴다). 승인 뒤의 보상은 {@link #settle}의 거절/재고부족 분기가 orderNo 멱등 환불로 한다.
     */
    /**
     * 복구를 시도했다는 사실만 <b>따로 커밋한다.</b> 상태는 안 바꾼다.
     *
     * <p>복구가 던지면 그 트랜잭션은 롤백되므로, 같은 트랜잭션에 적으면 이 기록도 같이
     * 사라진다. {@code REQUIRES_NEW} 로 떼어 남긴다.
     *
     * <p>왜 남기나: 복구 배치는 "이 시간 이상 머문 주문"을 {@code updatedAt} 으로 고르고
     * 한 번에 읽는 수에 상한이 있다. 실패한 건을 그대로 두면 <b>다음 회차에도 같은 앞자리를
     * 잡아</b> 상한 밖의 건이 영영 차례를 못 받는다. 여기 적으면 임계 시간을 다시 채워야
     * 해서 유예가 생기고, 오래된 순 정렬에서 뒤로 밀린다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markRecoveryAttempted(Long orderId) {
        orderRepository.findById(orderId).ifPresent(o -> {
            o.markRecoveryAttempted();
            orderRepository.saveAndFlush(o);
        });
    }

    @Transactional
    public Reservation reserve(String orderNo, String paymentKey, Money cardAmount,
                               long pointAmount, long walletAmount, long authenticatedUserId) {
        return reserve(orderNo, paymentKey, cardAmount, pointAmount, walletAmount,
                authenticatedUserId, 0);
    }

    /**
     * 할부 개월을 함께 받는 형태. 0이면 일시불이다.
     *
     * <p><b>여기에도 {@code @Transactional} 이 있어야 한다(#375).</b> {@code CheckoutService.confirm} 은 이 7인자
     * 메서드를 프록시로 직접 부른다. 어노테이션이 6인자 쪽에만 있던 동안(#13 ~ #375) 예약 단계가 트랜잭션 없이
     * 돌아, 월렛 부족 같은 뒤 단계 실패에도 주문 전이 · 결제 행 · 포인트 사용이 따로 커밋된 채 남았다.
     */
    @Transactional
    public Reservation reserve(String orderNo, String paymentKey, Money cardAmount,
                               long pointAmount, long walletAmount, long authenticatedUserId,
                               int installmentMonths) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));
        order.verifyOwner(authenticatedUserId); // IDOR 방지 — 무엇보다 먼저

        long requestedTotal;
        try {
            requestedTotal = Math.addExact(Math.addExact(cardAmount.minorUnit(), pointAmount), walletAmount);
        } catch (ArithmeticException e) {
            throw new OrderException("AMOUNT_OVERFLOW", "결제 금액이 허용 범위를 초과했습니다.");
        }
        order.verifyAmount(Money.krw(requestedTotal));

        order.startPayment(); // PENDING_PAYMENT → PAYMENT_IN_PROGRESS (조건부 전이, 이중지불 차단)

        if (pointAmount > 0) {
            pointService.use(order.getUserId(), pointAmount, orderNo); // 롤백 확실한 내부 자원 선점(in-tx)
        }

        Long paymentId = null;
        if (cardAmount.minorUnit() > 0) {
            paymentId = paymentService.beginApproval(orderNo, paymentKey, cardAmount, installmentMonths);
        }
        orderRepository.saveAndFlush(order);

        // 결제 시작에 재고를 잡는다(#374, AT_PAYMENT). PG 호출 전이라 모자라면 OUT_OF_STOCK 으로 이 트랜잭션 전체가
        // 롤백되고 승인은 나가지 않는다. 주문을 먼저 저장했으므로 조건부 UPDATE 가 컨텍스트를 비워도 전이는 남는다.
        if (stockReservationService.strategy() == StockReservationService.Strategy.AT_PAYMENT) {
            stockReservationService.reserve(orderNo, order.getItems().stream()
                    .map(i -> new StockReservationService.Line(i.getProductId(), i.getQuantity()))
                    .toList(), "payment");
        }

        // 월렛 차감은 맨 마지막 — 커밋되는 부수효과라 이후 단계 실패로 고아가 되지 않게 한다. orderNo로 멱등.
        if (walletAmount > 0) {
            walletService.use(order.getUserId(), walletAmount, orderNo);
        }
        return new Reservation(paymentId);
    }

    /**
     * Phase 3 — 확정/보상(짧은 tx). PG 결과({@code outcome})를 결제에 반영하고 주문을 확정하거나 보상한다.
     * PG 결과가 없으면(전액 포인트) 승인 성공으로 간주한다. 재고 차감은 승인 성공 시점에 한다(ADR-003).
     *
     * <p>이 메서드는 <b>재진입 가능</b>하다 — 멈춘 사가 복구가 PG 조회 결과로 이 메서드를 재실행해도
     * {@code applyResult}가 멱등(IN_PROGRESS일 때만 전이)이라 안전하다.
     */
    @Transactional
    public CheckoutResult settle(String orderNo, Long paymentId, Money cardAmount,
                                 long pointAmount, long walletAmount, ApprovalOutcome outcome) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> OrderException.orderNotFound(orderNo));

        ConfirmResult result;
        boolean cardApproved;
        if (paymentId != null) {
            result = paymentService.applyResult(paymentId, outcome); // 결제 확정(멱등), 이 tx에 합류
            cardApproved = result.isApproved();
        } else {
            result = null; // 전액 포인트 — PG 결과 없음
            cardApproved = true;
        }

        if (cardApproved) {
            // 승인 성공 시점 재고 차감(ADR-003). tryDeduct(예외 없는 boolean) — 부족해도 tx는 깨끗이 커밋하고 보상.
            List<OrderItem> deducted = new ArrayList<>();
            // 결제 시작 · 주문 생성 때 잡아 둔 재고가 있으면 확정만 한다(#374). 없으면 지금처럼 승인 뒤에 뺀다.
            boolean reserved = stockReservationService.claim(orderNo);
            boolean allDeducted = true;
            // 상품 ID 오름차순으로 잠근다 — 잠금 순서를 모든 트랜잭션에서 같게 만들어 데드락을 막는다.
            // 정렬이 없으면 잠금 순서가 주문 항목 순서를 따르므로, 장바구니에 같은 두 상품이 반대 순서로
            // 담긴 두 주문이 동시에 오면 서로가 상대의 행을 기다려 교착한다(A→B 대 B→A).
            // 단일 상품 주문에서는 드러나지 않아 실측에서도 안 잡혔다.
            List<OrderItem> lockOrdered = order.getItems().stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId))
                    .toList();
            for (OrderItem item : reserved ? List.<OrderItem>of() : lockOrdered) {
                if (stockDeductionService.tryDeduct(item.getProductId(), item.getQuantity())) {
                    deducted.add(item);
                } else {
                    allDeducted = false;
                    break;
                }
            }
            if (allDeducted) {
                order.markPaid();
                // 실결제액(카드+월렛, 포인트 사용분 제외) 기준 적립 — 포인트로 포인트를 버는 이중적립 방지.
                // earn은 orderNo 멱등이라 복구가 이 성공분기를 재실행해도 이중적립되지 않는다.
                long paidByMoney = cardAmount.minorUnit() + walletAmount;
                pointService.earn(order.getUserId(), paidByMoney * EARN_RATE_PERCENT / 100, orderNo);
            } else {
                // 승인 후 재고 부족 → 자동 보상(망취소). 예외를 던지지 않아 tx는 깨끗이 커밋된다.
                for (OrderItem d : deducted) {
                    stockDeductionService.restore(d.getProductId(), d.getQuantity());
                }
                if (pointAmount > 0) {
                    pointService.restore(order.getUserId(), pointAmount, orderNo);
                }
                if (walletAmount > 0) {
                    walletService.restore(order.getUserId(), walletAmount, orderNo); // 예약 해제(멱등)
                }
                if (cardAmount.minorUnit() > 0) {
                    compensationService.enqueueNetworkCancel(orderNo, cardAmount.minorUnit(),
                            "재고 부족: 카드 승인 후 자동 망취소");
                }
                order.markFailed();
                orderRepository.saveAndFlush(order);
                return new CheckoutResult(orderNo, order.getStatus(),
                        (result != null ? result.status() : PaymentStatus.DONE),
                        "재고가 부족해 결제를 취소 처리했습니다. 승인된 카드는 자동으로 취소됩니다.");
            }
        } else if (result.isUnknown()) {
            // 미확정: 포인트·월렛 예약을 <b>그대로 유지</b>한다(결제가 실제로 승인됐을 수 있으므로). 주문은
            // PAYMENT_IN_PROGRESS로 두고, 복구 배치가 PG 조회로 확정한다 — DONE이면 예약분이 그대로 소비된 채
            // PAID가 되고, ABORTED면 아래 거절 분기가 그때 복원한다. 여기서 미리 복원하면, 이후 완결(SUCCESS
            // 분기)이 예약분을 재소비하지 않아 가맹점이 그만큼 덜 걷는다(자금 손실).
        } else {
            // 명시적 거절: 선점 포인트·월렛 예약 해제(RESTORE), 재시도 위해 주문 PENDING_PAYMENT로 복귀.
            // 해제는 멱등이고, USE 멱등이 활성예약(USE−RESTORE−REFUND) 기준이라 재시도 시 다시 차감된다
            // (거절→재시도 이중무료 방지). point.restore와 wallet.restore가 대칭이다.
            if (pointAmount > 0) {
                pointService.restore(order.getUserId(), pointAmount, orderNo);
            }
            if (walletAmount > 0) {
                walletService.restore(order.getUserId(), walletAmount, orderNo);
            }
            // 결제 시작에 잡은 재고는 되돌린다(#374). 다시 시도하면 예약 단계가 다시 잡는다.
            // 주문 생성에 잡은 재고(AT_ORDER)는 두고, 주문 만료가 되돌린다.
            if (stockReservationService.strategy() == StockReservationService.Strategy.AT_PAYMENT) {
                stockReservationService.release(orderNo, "payment_declined");
            }
            order.revertToPending();
        }

        orderRepository.saveAndFlush(order);
        PaymentStatus paymentStatus = (result != null) ? result.status() : PaymentStatus.DONE;
        String message = (result != null) ? result.message() : "포인트 전액 결제 완료";
        return new CheckoutResult(orderNo, order.getStatus(), paymentStatus, message);
    }
}
