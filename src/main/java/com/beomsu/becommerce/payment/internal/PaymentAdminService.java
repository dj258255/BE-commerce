package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.recovery.PaymentRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 미확정(UNKNOWN) 결제 운영 어드민 — 방치된 미확정 결제를 조회하고 수동 복구를 트리거한다.
 *
 * <p>{@link PaymentRecoveryService#recoverUnknownPayments()}는 스케줄러가 있지만
 * ({@code payment.recovery.enabled=true}), 운영이 장애 복구 직후 즉시 한 번 돌리고 싶을 때
 * 이 어드민으로 수동 실행한다. 실제 확정 로직은 복구 서비스에 위임한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentAdminService {

    private final PaymentRepository paymentRepository;
    private final PaymentRecoveryService recoveryService;

    /** 미확정(UNKNOWN) 결제 페이지. */
    @Transactional(readOnly = true)
    public Page<UnknownPaymentView> listUnknown(Pageable pageable) {
        return paymentRepository.findByStatus(PaymentStatus.UNKNOWN, pageable)
                .map(p -> new UnknownPaymentView(p.getId(), p.getOrderNo(), p.getAmount(),
                        p.getStatus(), p.getRequestedAt()));
    }

    /** 미확정 결제 복구를 즉시 1회 실행한다. 반환값은 처리한 건수. */
    public int recover() {
        return recoveryService.recoverUnknownPayments();
    }

    /**
     * 단건 결제를 PG 조회로 즉시 확정(강제 동기화)한다.
     *
     * <p>웹훅 누락으로 특정 결제가 UNKNOWN/IN_PROGRESS에 방치됐을 때, 배치 전체 복구를 기다리지 않고
     * 그 한 건만 PG에 조회해 확정한다. 실제 조회·상태전이·영속은 배치 복구와 동일한
     * {@link PaymentRecoveryService#resolveByPaymentKey(String)}에 위임한다(이미 확정된 건이면 멱등 no-op).
     * 위임이 상태를 바꾸고 flush하므로, 그 뒤 같은 트랜잭션에서 <b>다시 읽어</b> 확정된 최신 상태를 응답한다.
     */
    @Transactional
    public PaymentSyncView sync(long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        recoveryService.resolveByPaymentKey(payment.getPaymentKey());
        Payment synced = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        return new PaymentSyncView(synced.getId(), synced.getOrderNo(),
                synced.getStatus().name(), "PG 조회로 상태를 동기화했습니다");
    }
}
