package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.catalog.StockReservationService;

import com.beomsu.becommerce.order.recovery.CheckoutRecoveryService;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.payment.ApprovalOutcome;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.queue.QueueService;
import com.beomsu.becommerce.shared.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 체크아웃(주문 생성 + 결제 승인 오케스트레이션) 애플리케이션 서비스 — order 모듈의 공개 진입점.
 *
 * <p>금액 위변조 검증은 total_amount의 소유자인 주문이 담당하고(이 모듈의 핵심), 실제 PG 승인은
 * payment 모듈({@link PaymentService})에 위임한다. 재고 차감은 ADR-003에 따라 <b>승인 성공 시점</b>에 한다.
 *
 * <p><b>체크아웃 사가(ADR-007)</b>: {@code confirm}은 클래스가 아니라 오케스트레이터 한 메서드로,
 * 예약({@link CheckoutTx#reserve}, tx) → PG 승인({@link PaymentService#pgApprove}, <b>tx 밖</b>) →
 * 확정({@link CheckoutTx#settle}, tx) 3단계다. PG 외부 콜 동안 DB 커넥션을 붙잡지 않아, 느린 PG가
 * 커넥션 풀을 마르게 하지 않는다. 클래스 레벨 {@code @Transactional}을 떼고 각 단계만 트랜잭션 경계로 둔다.
 */
@Service
public class CheckoutService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CheckoutService.class);

    private final PaymentService paymentService;
    private final CheckoutTx checkoutTx;
    private final CheckoutRecoveryService recoveryService;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final QueueService queueService;
    private final StockReservationService stockReservationService;
    private final List<Long> gateProductIds;
    private final String gateEventId;

    public CheckoutService(PaymentService paymentService,
                           CheckoutTx checkoutTx,
                           CheckoutRecoveryService recoveryService,
                           OrderRepository orderRepository,
                           ProductRepository productRepository,
                           QueueService queueService,
                           StockReservationService stockReservationService,
                           @Value("${app.queue.gate.product-ids:}") List<Long> gateProductIds,
                           @Value("${app.queue.gate.event-id:drop}") String gateEventId) {
        this.paymentService = paymentService;
        this.checkoutTx = checkoutTx;
        this.recoveryService = recoveryService;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.queueService = queueService;
        this.stockReservationService = stockReservationService;
        this.gateProductIds = gateProductIds;
        this.gateEventId = gateEventId;
    }

    /**
     * 주문 생성 — totalAmount를 확정 저장한다(이후 금액 위변조 검증의 기준값). 재고는 차감하지 않는다.
     *
     * <p>가격은 클라이언트가 보낸 값이 아니라 서버의 {@link Product} 카탈로그에서 조회한다.
     * 이래야 금액 위변조 검증의 기준값 자체를 신뢰할 수 있다.
     */
    @Transactional
    public CreateOrderResult createOrder(long userId, List<OrderLine> lines) {
        // 대기열 게이트(옵트인): 이벤트 상품만 서버가 대기열 입장을 강제한다(권고→강제).
        // 게이트 목록이 비어 있으면(기본) 이 블록은 스트림 한 번 안 돌고 건너뛴다 — 대상 없으면 무비용,
        // 기존 주문 경로는 100% 불변. 주문 생성이 서버 최초 쓰기 지점이라 여기서 막아야
        // DB(주문 INSERT)·이후 결제까지 아무 비용이 들지 않는다.
        if (!gateProductIds.isEmpty()
                && lines.stream().anyMatch(l -> gateProductIds.contains(l.productId()))
                && !queueService.hasEntryPass(gateEventId, String.valueOf(userId))) {
            throw new OrderException("QUEUE_PASS_REQUIRED", "선착순 대기열 입장 후 주문할 수 있습니다.");
        }
        List<OrderItem> items = lines.stream()
                .map(l -> {
                    Product product = productRepository.findById(l.productId())
                            .orElseThrow(() -> OrderException.productNotFound(l.productId()));
                    return OrderItem.of(product.getProductId(), product.getName(),
                            product.getPrice(), l.quantity());
                })
                .toList();
        // 재고를 언제 잡을지(#374). 기본(NONE)은 여기서 아무것도 하지 않는다(ADR-003: 승인 뒤 차감).
        List<StockReservationService.Line> stockLines = items.stream()
                .map(i -> new StockReservationService.Line(i.getProductId(), i.getQuantity()))
                .toList();
        StockReservationService.Strategy strategy = stockReservationService.strategy();
        if (strategy == StockReservationService.Strategy.CHECK) {
            stockReservationService.checkAvailable(stockLines);   // 여유만 본다. 잡지 않는다
        }
        Order order = Order.create(userId, items);
        if (strategy == StockReservationService.Strategy.AT_ORDER) {
            // 먼저 flush 한다. 예약의 조건부 UPDATE 가 영속성 컨텍스트를 비워도 주문 INSERT 가 남게.
            // 모자라면 OUT_OF_STOCK 이 이 트랜잭션을 롤백해 주문도 만들어지지 않는다
            orderRepository.saveAndFlush(order);
            stockReservationService.reserve(order.getOrderNo(), stockLines, "order");
        } else {
            orderRepository.save(order);
        }
        return new CreateOrderResult(order.getOrderNo(), order.getTotalAmount(), order.getExpiresAt());
    }

    /**
     * 복합결제 승인(포인트+카드). 서버 내부 처리 순서(10-API-스펙):
     * <ol>
     *   <li>주문 로드 (없으면 ORDER_NOT_FOUND)</li>
     *   <li>금액 위변조 검증 — 카드금액 + 포인트금액 + 월렛금액 == 주문 총액. 불일치 시 AMOUNT_MISMATCH (PG 호출 이전 차단)</li>
     *   <li>주문 상태 조건부 전이 PENDING_PAYMENT → PAYMENT_IN_PROGRESS (이중 지불 차단)</li>
     *   <li><b>포인트 선점</b> — 롤백이 확실한 내부 자원을 먼저 잡는다</li>
     *   <li>카드 승인 호출(payment 모듈 위임). 전액 포인트(cardAmount==0)면 카드 호출 생략</li>
     *   <li>결과 분기: 승인/전액포인트 → 재고 차감 + PAID / 미확정 → 유지 / 거절 → 복귀.
     *       <b>카드 실패·미확정 시 선점한 포인트를 복원(보상 트랜잭션)한다.</b></li>
     * </ol>
     *
     * <p>{@code pointAmount == 0}이면 순수 카드결제로, 기존 단일 카드결제와 완전히 동일하게 동작한다.
     * userId는 주문에서 얻는다(order가 소유자).
     *
     * @param cardAmount  카드로 결제할 금액
     * @param pointAmount 포인트로 결제할 금액(요청에 없으면 0)
     */
    public CheckoutResult confirm(String orderNo, String paymentKey, Money cardAmount,
                                  long pointAmount, long walletAmount, long authenticatedUserId) {
        return confirm(orderNo, paymentKey, cardAmount, pointAmount, walletAmount,
                authenticatedUserId, 0);
    }

    /** 할부 개월을 함께 받는 형태. 0이면 일시불이다. */
    public CheckoutResult confirm(String orderNo, String paymentKey, Money cardAmount,
                                  long pointAmount, long walletAmount, long authenticatedUserId,
                                  int installmentMonths) {
        // 0. 음수 방어 — 음수 금액으로 검증 우회·오버플로를 시도할 수 없게 한다. (DB 없음)
        if (pointAmount < 0 || walletAmount < 0 || cardAmount.minorUnit() < 0) {
            throw new OrderException("INVALID_REQUEST", "결제 금액은 음수일 수 없습니다.");
        }

        // Phase 0 (tx 밖) — 미확정 해소: 이 주문에 아직 확정 안 된 결제가 있으면 <b>여기서</b> PG에 물어
        // 확정한다. 고객이 카드 A 타임아웃 뒤 카드 B로 다시 누르는 상황이다. 배치를 기다리면 60초인데
        // 고객은 이미 화면 앞에 있다.
        //
        // 이걸 안 하고 재시도를 받으면, 앞 결제가 실제로 승인돼 있었을 때 <b>이중결제</b>가 된다.
        // 멱등키는 못 막는다 — 카드를 바꾼 재시도는 다른 요청이라 새 키를 받는다(이슈 #2).
        resolvePendingAttempt(orderNo);

        // Phase 1 (tx) — 예약: 검증·주문 상태 전이(이중지불 잠금)·포인트/월렛 선점·결제 IN_PROGRESS 적재.
        // 커밋 후 DB 커넥션을 반납한다.
        CheckoutTx.Reservation reservation =
                checkoutTx.reserve(orderNo, paymentKey, cardAmount, pointAmount, walletAmount,
                        authenticatedUserId, installmentMonths);

        // Phase 2 (tx 밖) — PG 승인: 외부 HTTP 콜을 트랜잭션 밖에서 한다. 이 동안 DB 커넥션 0개 점유
        // → 느린 PG가 커넥션 풀을 마르게 하지 않는다(ADR-007). 카드 몫이 0이면(포인트+월렛 전액) PG 콜을 생략한다.
        ApprovalOutcome outcome = (cardAmount.minorUnit() > 0)
                ? paymentService.pgApprove(orderNo, paymentKey, cardAmount, installmentMonths)
                : null;

        // Phase 3 (tx) — 확정/보상: PG 결과를 결제·주문에 반영하고 재고 차감/보상까지 마친다.
        return checkoutTx.settle(orderNo, reservation.paymentId(), cardAmount, pointAmount, walletAmount, outcome);
    }

    /**
     * 이 주문에 확정 안 된 결제가 남아 있으면 지금 해소한다.
     *
     * <p>주문이 {@code PAYMENT_IN_PROGRESS}면 앞 시도가 미확정으로 멈춘 것이다. PG 조회로 확정하면
     * 둘 중 하나가 된다. 승인이었으면 주문이 {@code PAID}가 되어 아래 {@code reserve}가 불법 전이로
     * 막고(정상 — 이미 결제됐다), 승인이 아니었으면 {@code PENDING_PAYMENT}로 돌아와 재시도가 열린다.
     *
     * <p>조회에 실패해도 재시도를 막지 않는다. 그러면 주문이 {@code PAYMENT_IN_PROGRESS}에 남아
     * {@code reserve}가 막고, 복구 배치가 다음 주기에 다시 시도한다 — 즉 <b>고치기 전 동작</b>으로
     * 떨어질 뿐 이중결제로 가지 않는다.
     */
    private void resolvePendingAttempt(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAYMENT_IN_PROGRESS) {
            return;   // 미확정 없음 — 정상 경로
        }
        try {
            recoveryService.resolveNow(order);
        } catch (Exception e) {
            // 삼키는 이유는 위 javadoc 참고 — 실패하면 배치가 맡는다.
            log.warn("재시도 시점 미확정 해소 실패 orderNo={} : {}", orderNo, e.getMessage());
        }

        // 해소 결과를 다시 읽어 <무슨 일이 일어났는지>를 응답으로 구분한다. 여기서 그냥 진행시키면
        // 아래 startPayment 가 "허용되지 않은 상태 전이입니다"로 막는데, 고객은 카드가 거절된 건지
        // 우리가 막는 건지 알 수 없다.
        OrderStatus after = orderRepository.findByOrderNo(orderNo)
                .map(Order::getStatus).orElse(null);
        if (after == OrderStatus.PAID) {
            throw OrderException.alreadyPaid(orderNo);              // 앞 결제가 실제로 승인돼 있었다
        }
        if (after == OrderStatus.PAYMENT_IN_PROGRESS) {
            throw OrderException.paymentResultPending(orderNo);     // 아직 모른다 — 새 승인을 내보내지 않는다
        }
        // PENDING_PAYMENT 로 돌아왔다면 앞 결제는 승인이 아니었다. 재시도를 그대로 진행한다.
        //
        // <b>여기에 남는 창이 하나 있다.</b> 조회가 NOT_FOUND 를 줘서 앞 결제를 버렸는데, 그 승인이
        // 사실은 PG 에서 아직 진행 중이었을 수 있다. 그러면 뒤늦게 승인이 나 <b>둘 다 승인</b>된다.
        //
        // 이건 조회로는 못 막는다. 잡는 곳은 <b>대사</b>다. 버린 결제는 완료 이벤트를 안 내보내
        // 내부 기록이 없는데 PG 정산 파일에는 있으므로 EXTERNAL_ONLY 로 잡히고, 원인은
        // INTERNAL_RECORD_LOST 다 — 여덟 원인 중 유일하게 모델을 켜 둔 유형이다.
    }
}
