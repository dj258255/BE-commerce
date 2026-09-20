package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.internal.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 결제 도메인이 타임라인에 내주는 사실 (ADR-011).
 *
 * <p>결제는 이 프로젝트에서 <b>유일하게 전이 이력을 남기는</b> 도메인이다
 * ({@code payment_history}는 append-only). 주문은 상태를 덮어쓰므로 "언제 무엇에서 무엇으로
 * 바뀌었는가"를 알 수 있는 곳은 여기뿐이고, 대사 원인을 판단할 때 가장 중요한 재료가 된다.
 */
@Service
public class PaymentTimelineFacts {

    private final PaymentRepository paymentRepository;

    PaymentTimelineFacts(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * 이 주문의 결제와 그 전이 이력.
     *
     * <p>결제가 없으면 빈 목록이다 — 전액 포인트 결제이거나, 대사에서 외부에만 있는 건일 때
     * 실제로 일어난다. <b>오류가 아니다.</b>
     */
    @Transactional(readOnly = true)
    public List<PaymentFact> findByOrderNo(String orderNo) {
        return paymentRepository.findFirstByOrderNoOrderByRequestedAtDesc(orderNo)
                // 이력은 별도 저장소가 아니라 Payment 애그리거트가 들고 있다. 애그리거트 밖에서
                // 이력만 따로 읽을 수 없게 한 설계이므로, 여기서도 결제를 통해 접근한다.
                .map(payment -> payment.getHistories().stream()
                        .map(h -> new PaymentFact(
                                h.getCreatedAt(),
                                h.getFromStatus().name(),
                                h.getToStatus().name(),
                                h.getTriggeredBy().name(),
                                h.getReason(),
                                payment.getAmount(),
                                payment.getPgProvider(),
                                payment.getInstallmentMonths()))
                        .toList())
                .orElseGet(List::of);
    }

    /** 원장·결제이력이 쓰는 키를 해석한다. 없으면 empty(트레이드오프 5). */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> resolvePaymentId(String orderNo) {
        return paymentRepository.findFirstByOrderNoOrderByRequestedAtDesc(orderNo).map(Payment::getId);
    }


    /**
     * 대사 원인 판정에 쓰는 결제 사실 (ADR-012).
     *
     * <p>{@code balanceAmount}가 핵심이다 — 승인액에서 취소분을 뺀 잔여이므로,
     * <b>취소된 금액 = amount − balance</b>가 결정적으로 나온다.
     * "차액이 취소 금액과 같은가"를 추측이 아니라 산수로 판정할 수 있다.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<PaymentState> findState(String orderNo) {
        return paymentRepository.findFirstByOrderNoOrderByRequestedAtDesc(orderNo)
                .map(p -> new PaymentState(p.getAmount(), p.getBalanceAmount(),
                        p.getCancelCount(), p.getStatus().name(), p.getRequestedAt()));
    }

    /**
     * @param amount        승인 금액
     * @param balanceAmount 잔여 금액(취소분 차감 후)
     * @param cancelCount   취소 횟수. 0이면 취소가 없었다는 뜻이라 부분취소 가설을 배제할 수 있다
     * @param status        결제 상태
     * @param requestedAt   승인 요청 시각. 정산 파일 마감 직전인지 판단할 때 쓴다
     */
    public record PaymentState(long amount, long balanceAmount, int cancelCount,
                               String status, Instant requestedAt) {
        /** 취소된 금액. 승인액에서 잔여를 뺀 값이다. */
        public long canceledAmount() {
            return amount - balanceAmount;
        }
    }

    /** 전이 한 건. 상태 이름은 문자열로 내준다 — enum을 내주면 받는 쪽이 payment 내부에 묶인다. */
    public record PaymentFact(Instant at, String fromStatus, String toStatus,
                              String triggeredBy, String reason, long amount, String pgProvider,
                              int installmentMonths) {
    }
}
