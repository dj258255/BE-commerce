package com.beomsu.becommerce.payment.forcecancel;

import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.shared.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강제취소 maker-checker 운영 서비스 — 요청(maker)·승인(checker)·거부·조회를 오케스트레이션한다.
 *
 * <p>운영이 분쟁·오류 정정으로 결제를 강제 취소할 때, 한 사람이 단독으로 실행하지 못하도록
 * 요청과 승인을 분리한다. 요청자≠승인자 강제는 {@link ForceCancelRequest#approve(String)}의
 * 도메인 가드가 담당하고, 이 서비스는 승인이 통과했을 때에만 기존 {@link PaymentService#cancel}을
 * 재사용해 실제 취소를 실행한다. 취소가 실패하면 트랜잭션이 롤백돼 요청은 REQUESTED로 남는다.
 */
@Service
@RequiredArgsConstructor
public class ForceCancelService {

    private final ForceCancelRequestRepository repository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    /**
     * 강제취소 요청 생성(maker). 결제 존재와 취소액(>0)만 검증하고, 실제 취소는 하지 않는다.
     * 승인 대기(REQUESTED) 결재 레코드만 남긴다.
     */
    @Transactional
    public ForceCancelView request(long paymentId, long cancelAmount, String reason, String requester) {
        if (cancelAmount <= 0) {
            throw new PaymentException("INVALID_CANCEL_AMOUNT",
                    "취소 금액은 0보다 커야 합니다: " + cancelAmount);
        }
        paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("PAYMENT_NOT_FOUND",
                        "결제를 찾을 수 없습니다: " + paymentId));
        ForceCancelRequest saved = repository.save(
                ForceCancelRequest.request(paymentId, cancelAmount, reason, requester));
        return ForceCancelView.of(saved);
    }

    /**
     * 승인(checker) = 실행. 요청을 로드해 {@link ForceCancelRequest#approve(String)}로 maker-checker
     * 가드(요청자≠승인자)를 통과시킨 뒤, 그때에만 기존 {@link PaymentService#cancel}로 실제 결제를
     * 취소한다. cancel이 실패하면 트랜잭션 롤백으로 요청은 REQUESTED로 유지된다.
     */
    @Transactional
    public ForceCancelView approve(long requestId, String approver) {
        ForceCancelRequest req = load(requestId);
        req.approve(approver);   // maker-checker 가드(요청자==승인자면 MAKER_CHECKER_VIOLATION)
        // 가드 통과 후에만 실제 취소를 실행한다(기존 PaymentService.cancel 재사용).
        paymentService.cancel(req.getPaymentId(), Money.krw(req.getCancelAmount()), req.getReason());
        repository.saveAndFlush(req);
        return ForceCancelView.of(req);
    }

    /** 거부(checker) — REQUESTED → REJECTED. 실제 취소는 실행하지 않는다. */
    @Transactional
    public ForceCancelView reject(long requestId, String approver) {
        ForceCancelRequest req = load(requestId);
        req.reject(approver);
        repository.saveAndFlush(req);
        return ForceCancelView.of(req);
    }

    /** 상태별 강제취소 요청 페이지(운영 관측용). */
    @Transactional(readOnly = true)
    public Page<ForceCancelView> list(ForceCancelStatus status, Pageable pageable) {
        return repository.findByStatus(status, pageable)
                .map(ForceCancelView::of);
    }

    private ForceCancelRequest load(long requestId) {
        return repository.findById(requestId)
                .orElseThrow(() -> new PaymentException("FORCE_CANCEL_NOT_FOUND",
                        "강제취소 요청을 찾을 수 없습니다: " + requestId));
    }
}
