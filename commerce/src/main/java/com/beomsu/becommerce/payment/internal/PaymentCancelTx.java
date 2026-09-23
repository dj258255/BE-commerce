package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.order.internal.CheckoutTx;
import com.beomsu.becommerce.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 취소 사가의 <b>트랜잭션 경계</b> — 대상 확정(Phase 1)과 결과 반영(Phase 3)을 각각 짧은 트랜잭션으로
 * 담는다. 그 사이의 PG 취소 호출(Phase 2)은 {@link PaymentService}가 트랜잭션 밖에서 한다.
 *
 * <p><b>왜 나눴나</b> — 외부 API를 트랜잭션 안에서 부르면 두 가지가 깨진다. 첫째, 느린 PG가 응답할
 * 때까지 DB 커넥션을 붙잡아 폭주 시 풀이 마른다. 둘째, PG 취소가 성공한 뒤 커밋이 실패하면 롤백이
 * 오히려 불일치를 만든다(PG는 취소됐는데 우리 장부는 안 취소됨). 승인 경로가 {@code CheckoutTx}로
 * 같은 문제를 이미 푼 방식을 취소에도 그대로 적용한다.
 *
 * <p><b>왜 PG를 먼저 부르고 나중에 기록하나</b> — 반대 순서(기록 먼저)면 PG 취소가 실패했을 때
 * 우리만 취소로 남는다. PG 취소는 취소 건 단위 멱등키로 보호되므로, Phase 3이
 * 실패해 재시도해도 두 번 취소되지 않고 "이미 취소됨" 응답이 성공으로 흡수된다. 그래서
 * <b>바깥을 먼저 확정하고 우리 기록을 맞추는</b> 순서가 안전하다.
 *
 * <p>별도 빈으로 둔 이유: 비트랜잭션 오케스트레이터가 이 메서드들을 프록시로 호출해야
 * {@code @Transactional}이 실제 경계가 된다(자기호출이면 우회된다).
 */
@Component
@RequiredArgsConstructor
// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public class PaymentCancelTx {

    public static final List<PaymentStatus> CANCELABLE =
            List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher events;

    /**
     * 취소 대상 — Phase 2가 PG를 부르고 Phase 3이 다시 찾는 데 쓰는 키.
     *
     * <p>결제 식별자가 아니라 {@code paymentKey}로 잡는다. PG 취소가 이 키로 이뤄지므로, 재시도가
     * 같은 키로 같은 결제를 다시 집는다.
     *
     * <p>{@code cancelSeq}는 이 결제의 몇 번째 취소인가다. 실패한 취소는 순번을 소비하지 않으므로
     * 재시도는 같은 순번을 다시 계산하고(멱등), 같은 금액의 서로 다른 부분취소는 순번이 갈린다.
     */
    /**
     * 취소 대상.
     *
     * <p>{@code provider}는 이 결제를 승인한 PG다. 취소를 그리로 보내야 한다.
     */
    public record CancelTarget(String paymentKey, int cancelSeq, String provider) {}

    /**
     * Phase 1 — 취소 대상 확정(짧은 tx, 읽기 전용). 취소 가능 여부까지 여기서 걸러
     * <b>불가능한 취소를 PG에 보내지 않는다</b>. 상태는 바꾸지 않는다.
     */
    @Transactional(readOnly = true)
    public CancelTarget resolveByOrderNo(String orderNo, Money cancelAmount) {
        Payment payment = paymentRepository.findFirstByOrderNoAndStatusIn(orderNo, CANCELABLE)
                .orElseThrow(() -> notCancelable(orderNo));
        payment.validateCancelable(cancelAmount);
        return new CancelTarget(payment.getPaymentKey(), payment.getCancelCount() + 1,
                payment.getPgProvider());
    }

    /**
     * 취소 가능한 결제가 없을 때, <b>이미 취소된 것</b>과 <b>아예 없는 것</b>을 나눈다.
     *
     * <p>보상 실행기가 이 둘을 같은 코드로 받으면 "없다 = 이미 보상됐다"로 읽는다.
     * 그런데 없는 이유는 여럿이다 — 잘못된 주문번호, 승인된 적 없음, 전파 지연.
     * <b>확정할 수 없는 것을 완료로 확정하면 보상이 조용히 사라진다.</b>
     */
    private PaymentException notCancelable(String orderNo) {
        boolean everExisted = paymentRepository.existsByOrderNo(orderNo);
        if (everExisted) {
            // 결제는 있는데 취소 가능 상태가 아니다 = 이미 취소됐거나 종결됐다.
            return new PaymentException("PAYMENT_ALREADY_SETTLED",
                    "이미 취소됐거나 취소할 수 없는 상태입니다: " + orderNo);
        }
        return new PaymentException("PAYMENT_NOT_FOUND",
                "취소할 결제를 찾을 수 없습니다: " + orderNo);
    }

    /** Phase 1 — 결제 식별자로 취소 대상을 확정한다. */
    @Transactional(readOnly = true)
    public CancelTarget resolveById(Long paymentId, Money cancelAmount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        payment.validateCancelable(cancelAmount);
        return new CancelTarget(payment.getPaymentKey(), payment.getCancelCount() + 1,
                payment.getPgProvider());
    }

    /**
     * Phase 3 — PG 취소 성공을 결제에 반영(짧은 tx)하고 취소 이벤트를 발행한다.
     *
     * <p><b>멱등은 상태가 아니라 취소 순번으로 판정한다.</b> 상태로 보면 부분취소가 막히지 않는다 —
     * {@code PARTIAL_CANCELED}는 여전히 취소 가능한 상태라 재진입이 그대로 통과해 잔액이 한 번 더
     * 깎인다. 이 결제에 N번째 취소가 이미 반영됐는지를 직접 물으면 전액·부분이 같은 규칙으로 닫힌다.
     */
    @Transactional
    public void apply(String paymentKey, int cancelSeq, Money cancelAmount, String reason,
                      String pgTransactionKey) {
        Payment payment = paymentRepository.findByPaymentKey(paymentKey)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentKey));
        if (payment.getCancelCount() >= cancelSeq) {
            return; // 이 취소는 이미 반영됐다 — 재시도가 멱등하게 끝난다
        }
        if (!CANCELABLE.contains(payment.getStatus())) {
            return; // 이미 전액 취소 등으로 확정됨
        }
        // PG 취소 거래키를 이력에 남긴다. 취소 대사는 이 키로만 PG 기록과 조인할 수 있는데,
        // 여기서 버리면 나중에 "이 취소가 PG의 어느 거래였나"를 되짚을 방법이 사라진다.
        String auditReason = pgTransactionKey == null || pgTransactionKey.isBlank()
                ? reason
                : reason + " · PG거래키=" + pgTransactionKey;
        payment.cancel(cancelAmount, TriggeredBy.USER, auditReason);

        // 취소 전이를 이벤트 발행 전에 flush로 확정한다. (managed 엔티티라 커밋 시 dirty-check로도
        // flush되지만, 발행 순서상 상태를 먼저 못박아 둔다.)
        paymentRepository.saveAndFlush(payment);

        boolean fullyCanceled = payment.getStatus() == PaymentStatus.CANCELED;
        // 취소 후 잔액(절대값)을 실어 정산이 멱등하게 반영하게 한다(델타 차감이 아니라 절대 잔액 세팅).
        // canceledAt은 <지금>이 맞다 — 취소가 방금 이 트랜잭션에서 일어났기 때문이다.
        // 소비 쪽에서 Instant.now()를 쓰면 아웃박스 지연만큼 거래일이 밀리므로, 사실이 일어난
        // 시각을 여기서 못박아 실어 보낸다(ADR-013).
        events.publishEvent(new PaymentCanceledEvent(
                payment.getOrderNo(), payment.getId(), cancelSeq, cancelAmount.minorUnit(),
                payment.getBalanceAmount(), fullyCanceled, java.time.Instant.now()));
    }
}
