package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.recovery.PaymentRecoveryService;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.internal.PaymentHistoryView;
import com.beomsu.becommerce.payment.internal.PaymentCancelView;
import com.beomsu.becommerce.payment.internal.PaymentCancelTx;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.StuckPaymentInfo;
import com.beomsu.becommerce.payment.pg.PgApproveCommand;
import com.beomsu.becommerce.payment.pg.PgApproveResult;
import com.beomsu.becommerce.payment.pg.PgCancelCommand;
import com.beomsu.becommerce.payment.pg.PgCancelResult;
import com.beomsu.becommerce.payment.pg.PgCancelNotRetryableException;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 결제 애플리케이션 서비스 — payment 모듈의 공개 진입점.
 *
 * <p>승인/취소를 오케스트레이션한다. 금액 위변조 검증은 주문 금액의 소유자인 order 모듈이
 * 담당하므로, 이 서비스는 이미 검증된 금액을 받는다고 가정한다.
 * PG 호출 결과는 3-상태(SUCCESS/FAILED/TIMEOUT)로 나뉘며, TIMEOUT은 UNKNOWN으로 보존한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final PaymentCancelTx cancelTx;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;

    /**
     * 승인 1단계 — 결제를 IN_PROGRESS로 적재한다(예약). PG 콜 <b>전</b>에 짧은 트랜잭션으로 커밋된다.
     *
     * <p>ADR-007의 사가 이행: 체크아웃은 예약(tx)→PG(tx 밖)→확정(tx) 3단계로, PG 외부 콜 동안 DB
     * 커넥션을 붙잡지 않는다. 이 메서드가 그 1단계다. 레코드를 PG 콜 전에 커밋해 두므로, 크래시 후에도
     * 복구 배치가 이 IN_PROGRESS 레코드를 찾아 PG 조회로 확정할 수 있다(charge 크래시 안전성↑).
     *
     * @return 적재된 결제의 id
     */
    @Transactional
    public Long beginApproval(String orderNo, String paymentKey, Money amount) {
        return beginApproval(orderNo, paymentKey, amount, 0);
    }

    /** 할부 개월을 함께 받는 형태. 0이면 일시불이다. */
    @Transactional
    public Long beginApproval(String orderNo, String paymentKey, Money amount, int installmentMonths) {
        Payment payment = Payment.initiate(orderNo, amount, installmentMonths);
        payment.startApproval(paymentKey);
        paymentRepository.save(payment);
        return payment.getId();
    }

    /**
     * 승인 2단계 — PG 승인 API를 호출한다. <b>트랜잭션 밖</b>에서 실행되어 외부 응답 동안 DB 커넥션을
     * 점유하지 않는다(느린 PG가 커넥션 풀을 마르게 하지 않게 하는 핵심). 결과만 반환하고 DB는 건드리지 않는다.
     * 내부 PG 타입은 모듈 노출용 {@link ApprovalOutcome}로 매핑해 돌려준다(order가 불투명하게 전달).
     */
    public ApprovalOutcome pgApprove(String orderNo, String paymentKey, Money amount) {
        return pgApprove(orderNo, paymentKey, amount, 0);
    }

    /** 할부 개월을 함께 보내는 형태. 0이면 일시불이다. */
    public ApprovalOutcome pgApprove(String orderNo, String paymentKey, Money amount, int installmentMonths) {
        PgApproveResult result = pgClient.approve(
                new PgApproveCommand(paymentKey, orderNo, amount.minorUnit(), installmentMonths));
        // 관측성: 승인 결과를 결과별로 계측한다. Grafana의 "결제 성공률" 패널의 소스.
        meterRegistry.counter("payment.confirm", "outcome", result.outcome().name().toLowerCase())
                .increment();
        return new ApprovalOutcome(
                ApprovalOutcome.Result.valueOf(result.outcome().name()), result.method(),
                result.failReason(), result.provider(), result.cardFingerprint());
    }

    /**
     * 승인 3단계 — PG 결과를 결제 레코드에 반영한다(확정). 짧은 트랜잭션. 호출자(체크아웃 확정 tx)에 합류한다.
     *
     * <p><b>멱등</b>: IN_PROGRESS일 때만 전이한다. 재배달·복구로 두 번 호출돼도 이미 확정된(DONE/ABORTED/
     * UNKNOWN) 결제는 현재 상태를 그대로 반영한 결과를 돌려준다 — 멈춘 사가 복구가 이 메서드를 재실행해도 안전.
     */
    @Transactional
    public ConfirmResult applyResult(Long paymentId, ApprovalOutcome outcome) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND", "결제 없음: " + paymentId));

        if (payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            // 이미 확정됨(재실행) — 현재 상태를 그대로 반영해 멱등 반환.
            return new ConfirmResult(payment.getId(), payment.getStatus(),
                    payment.getMethod(), "이미 처리됨");
        }

        ConfirmResult confirmResult = switch (outcome.result()) {
            case SUCCESS -> {
                // 어느 PG가 승인했는지 함께 적는다. 취소를 어디로 보낼지가 여기서 정해진다
                payment.approve(outcome.method(), outcome.provider(), outcome.cardFingerprint());
                events.publishEvent(new PaymentConfirmedEvent(
                        payment.getOrderNo(), payment.getId(), payment.getAmount(), payment.getApprovedAt()));
                yield new ConfirmResult(payment.getId(), payment.getStatus(), outcome.method(), "승인 완료");
            }
            case FAILED -> {
                payment.abort(outcome.failReason());
                yield new ConfirmResult(payment.getId(), payment.getStatus(), null, outcome.failReason());
            }
            case TIMEOUT -> {
                // 미확정: 실패로 단정하지 않는다. 복구 배치가 확정한다.
                payment.markUnknown(outcome.failReason());
                yield new ConfirmResult(payment.getId(), payment.getStatus(), null,
                        "결제 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.");
            }
        };
        // 이 트랜잭션 안 managed 엔티티라 커밋 시 flush되지만, 전이 확정을 명시하려 saveAndFlush(pay-26).
        paymentRepository.saveAndFlush(payment);
        return confirmResult;
    }

    /**
     * 멈춘 사가 복구용 — 주문의 카드 결제가 IN_PROGRESS로 멈춰 있으면 PG 조회로 확정(DONE/ABORTED)하고,
     * 확정 결과를 order 모듈에 노출한다. 카드 결제가 없으면(전액 포인트) empty를 돌려준다.
     *
     * <p>PG 조회→확정 로직은 {@link PaymentRecoveryService#resolveByPaymentKey}와 같은 계열이다.
     * 이미 확정된 결제는 재조회하지 않고 현재 상태를 매핑해 돌려준다(멱등).
     */
    @Transactional
    public Optional<StuckPaymentInfo> resolveStuckPayment(String orderNo) {
        Optional<Payment> found = paymentRepository.findFirstByOrderNoOrderByRequestedAtDesc(orderNo);
        if (found.isEmpty()) {
            return Optional.empty(); // 카드 결제 없음(전액 포인트)
        }
        Payment payment = found.get();
        // IN_PROGRESS(예약 후 크래시)뿐 아니라 UNKNOWN(타임아웃 미확정)도 PG 조회로 재확정한다 —
        // PaymentRecoveryService.resolveByPaymentKey와 같은 계열. UNKNOWN을 재조회하지 않으면 타임아웃
        // 체크아웃이 이 배치 단독으로 영구 미완결된다.
        if (payment.getStatus() == PaymentStatus.IN_PROGRESS
                || payment.getStatus() == PaymentStatus.UNKNOWN) {
            PgQueryResult pg = pgClient.query(payment.getPaymentKey(), payment.getPgProvider());
            switch (pg.status()) {
                case APPROVED -> {
                    payment.confirmByRecovery(pg.method());
                    paymentRepository.saveAndFlush(payment);
                    events.publishEvent(new PaymentConfirmedEvent(
                            payment.getOrderNo(), payment.getId(), payment.getAmount(), payment.getApprovedAt()));
                }
                // NOT_FOUND(승인 미완료)·CANCELED(PG에서 취소됨) 모두 "성공한 결제 아님" → ABORTED로 확정한다.
                // IN_PROGRESS→CANCELED는 전이표상 불법이므로 abortByRecovery로 통일한다(IN_PROGRESS·UNKNOWN 모두 합법).
                case NOT_FOUND -> {
                    payment.abortByRecovery("복구: PG에 결제 정보 없음(승인 미완료)");
                    paymentRepository.saveAndFlush(payment);
                }
                case CANCELED -> {
                    payment.abortByRecovery("복구: PG에서 취소됨(승인 미완료)");
                    paymentRepository.saveAndFlush(payment);
                }
            }
        }
        ApprovalOutcome outcome = (payment.getStatus() == PaymentStatus.DONE)
                ? new ApprovalOutcome(ApprovalOutcome.Result.SUCCESS, payment.getMethod(), null)
                : new ApprovalOutcome(ApprovalOutcome.Result.FAILED, null, "복구: 승인 미완료");
        return Optional.of(new StuckPaymentInfo(payment.getId(), payment.getAmount(), outcome));
    }

    /**
     * 결제 취소(전액/부분) — 취소 사가의 오케스트레이터.
     *
     * <p>이 메서드에는 {@code @Transactional}이 없다. 대상 확정(tx) → <b>PG 취소(tx 밖)</b> →
     * 결과 반영(tx) 3단계로 나눠, 느린 PG가 DB 커넥션을 붙잡지 않게 한다({@link PaymentCancelTx}).
     */
    public void cancel(Long paymentId, Money cancelAmount, String reason) {
        PaymentCancelTx.CancelTarget target = cancelTx.resolveById(paymentId, cancelAmount);
        PgCancelResult result = pgClient.cancel(new PgCancelCommand(
                target.paymentKey(), cancelAmount.minorUnit(), reason, target.cancelSeq(),
                target.provider()));
        cancelTx.apply(target.paymentKey(), target.cancelSeq(), cancelAmount, reason,
                result.transactionKey());
    }

    /**
     * 주문번호로 성공한 결제를 찾아 취소한다. order 모듈의 취소 오케스트레이션이 호출한다.
     * 전액 포인트 결제(카드 결제 없음)면 취소할 결제가 없으므로 호출되지 않는다.
     */
    public void cancelByOrderNo(String orderNo, Money cancelAmount, String reason) {
        PaymentCancelTx.CancelTarget target = cancelTx.resolveByOrderNo(orderNo, cancelAmount);
        PgCancelResult result;
        try {
            result = pgClient.cancel(new PgCancelCommand(
                    target.paymentKey(), cancelAmount.minorUnit(), reason, target.cancelSeq(),
                    target.provider()));
        } catch (PgCancelNotRetryableException e) {
            // PG 내부 예외를 모듈 밖으로 새게 두지 않는다. 호출자(보상 실행기)는 "재시도해도 같다"는
            // 사실만 알면 되므로 payment 모듈의 코드로 번역해 넘긴다.
            throw new PaymentException("CANCEL_NOT_RETRYABLE", e.getMessage());
        }
        cancelTx.apply(target.paymentKey(), target.cancelSeq(), cancelAmount, reason,
                result.transactionKey());
    }

    /** 주문의 카드 취소 가능 잔액. 성공한 결제가 없으면(전액 포인트 등) 0. */
    @Transactional(readOnly = true)
    public long cardBalance(String orderNo) {
        return paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, PaymentCancelTx.CANCELABLE)
                .map(Payment::getBalanceAmount)
                .orElse(0L);
    }

    /**
     * 주문의 <b>승인 완료(DONE)</b> 결제 금액 — 차지백 검증용. 승인된 카드 결제가 없으면 empty.
     * dispute가 차지백 금액이 원 결제 금액을 넘지 않는지, 실존 주문인지 대조할 때 쓴다(가짜/과다 차지백 차단).
     */
    @Transactional(readOnly = true)
    public Optional<Long> approvedAmountByOrderNo(String orderNo) {
        return paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, List.of(PaymentStatus.DONE))
                .map(Payment::getAmount);
    }

    // --- 조회(read-only) 지원: order 모듈의 조회 오케스트레이션이 소유권 검증 후 호출한다 ---

    /**
     * 결제의 주문번호 — 소유권 검증용. 결제↔주문 매핑은 order가 소유하므로, order가 이 orderNo로
     * 주문을 로드해 {@code verifyOwner}로 IDOR을 막은 뒤에만 상세 조회로 넘어간다. 없으면 empty(404).
     */
    @Transactional(readOnly = true)
    public Optional<String> orderNoOf(long paymentId) {
        return paymentRepository.findById(paymentId).map(Payment::getOrderNo);
    }

    /**
     * 결제의 PG 결제 키 — fraud 모듈의 비동기 사후탐지가 결제 완료 이벤트를 받아 카드(=paymentKey)
     * 기준 룰을 재평가하기 위해 조회한다. 결제 이벤트는 Zero-Payload라 카드 키를 싣지 않으므로,
     * fraud가 paymentId로 여기서 키를 되읽는다. 없으면 empty(사후탐지는 조용히 skip).
     */
    @Transactional(readOnly = true)
    public Optional<String> paymentKeyOf(long paymentId) {
        return paymentRepository.findById(paymentId).map(Payment::getPaymentKey);
    }

    /**
     * 사후 탐지가 이력을 묶을 키 — 카드 지문이 있으면 그것, 없으면 결제 키.
     *
     * <p><b>폴백이 뜻하는 것을 알고 써야 한다.</b> 결제 키로 떨어지면 그 건은 <b>자기 자신하고만
     * 묶이므로</b> 창에 한 줄만 들어온다. 카드 결제가 아니거나 PG 가 카드 정보를 안 주는 경우이고,
     * 이미 쌓인 결제도 전부 여기에 해당한다. 그 상태에서 나온 점수는 <b>과거를 못 본 점수</b>다.
     */
    @Transactional(readOnly = true)
    public Optional<String> historyKeyOf(long paymentId) {
        return paymentRepository.findById(paymentId)
                .map(p -> p.getCardFingerprint() != null ? p.getCardFingerprint() : p.getPaymentKey());
    }

    /** 결제 상세(이력·취소 포함). 엔티티가 아닌 뷰 record로 반환해 모듈 경계를 지킨다. 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<PaymentDetailView> getDetail(long paymentId) {
        return paymentRepository.findById(paymentId).map(this::toDetailView);
    }

    private PaymentDetailView toDetailView(Payment payment) {
        List<PaymentHistoryView> history = payment.getHistories().stream()
                .map(h -> new PaymentHistoryView(h.getFromStatus().name(), h.getToStatus().name(),
                        h.getTriggeredBy().name(), h.getCreatedAt()))
                .toList();
        // 별도 취소 엔티티가 없으므로 취소를 상태 이력에서 투영한다: toStatus가 (부분)취소인 전이만 필터.
        List<PaymentCancelView> cancels = payment.getHistories().stream()
                .filter(h -> h.getToStatus() == PaymentStatus.CANCELED
                        || h.getToStatus() == PaymentStatus.PARTIAL_CANCELED)
                .map(h -> new PaymentCancelView(h.getToStatus().name(), h.getReason(), h.getCreatedAt()))
                .toList();
        return new PaymentDetailView(payment.getId(), payment.getOrderNo(),
                payment.getStatus().name(), payment.getAmount(), payment.getBalanceAmount(),
                history, cancels);
    }

    /**
     * 주문의 대표 결제 상태 — 주문 조회 뷰에 함께 싣는다. 재시도로 결제가 여러 건이면 <b>최신 시도</b>
     * (requestedAt 내림차순)의 상태를 대표로 삼는다. 이래야 UNKNOWN→DONE 확정 진행을 주문 폴링으로도
     * 관찰할 수 있다. 결제 시도가 아예 없으면 empty(주문 뷰에서 null/"NONE").
     */
    @Transactional(readOnly = true)
    public Optional<String> paymentStatusByOrderNo(String orderNo) {
        return paymentRepository.findFirstByOrderNoOrderByRequestedAtDesc(orderNo)
                .map(p -> p.getStatus().name());
    }

}
