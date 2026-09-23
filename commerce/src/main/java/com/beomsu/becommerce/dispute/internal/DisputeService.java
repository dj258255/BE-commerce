package com.beomsu.becommerce.dispute.internal;

import com.beomsu.becommerce.dispute.DisputeLostEvent;
import com.beomsu.becommerce.payment.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 분쟁 애플리케이션 서비스 — dispute 모듈의 공개 진입점.
 *
 * <p>차지백 수신({@link #openFromChargeback})은 {@code chargebackId}로 <b>멱등</b>하다: 같은 차지백이
 * 두 번 와도 분쟁은 하나만 만든다(중복 웹훅 안전). 증빙 제출·승패 확정은 엔티티 상태머신을 태우고,
 * <b>패소(LOST) 시 {@link DisputeLostEvent}를 발행</b>해 원장 역분개를 트리거한다 — 원장은 이
 * 이벤트를 아웃박스로 유실 없이 받아 (disputeId 유니크로) 멱등하게 역분개한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    /** 차지백 대응(증빙 제출) 기한 — 수신 시점 + 7일. 실무 카드사 규약(보통 7~20일)의 보수적 근사. */
    private static final Duration RESPOND_WINDOW = Duration.ofDays(7);

    private final DisputeRepository repository;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher events;

    /**
     * 차지백 수신 → 분쟁 개시. {@code chargebackId}로 멱등하다: 이미 있으면 기존 분쟁을 그대로 반환해
     * 중복 웹훅에도 분쟁이 하나만 생기게 한다. 동시 수신으로 UNIQUE 제약을 스치면 재조회로 흡수한다.
     */
    public DisputeView openFromChargeback(String chargebackId, String orderNo, Long paymentId,
                                          long amount, String reason) {
        var existing = repository.findByChargebackId(chargebackId);
        if (existing.isPresent()) {
            return DisputeView.from(existing.get());
        }
        // 원 결제 대조 — 실존하는 승인 완료 결제여야 하고, 차지백 금액이 그 금액을 넘을 수 없다.
        // 페이로드를 전면 신뢰하면 가짜 orderNo·과다 금액으로 원장이 오염되므로(패소 확정 시 역분개),
        // 개시 시점에 막는다. 웹훅은 이 예외를 200으로 흡수(로깅)해 재전송 폭주를 피한다.
        long approved = paymentService.approvedAmountByOrderNo(orderNo)
                .orElseThrow(() -> new DisputeException("DISPUTE_NO_PAYMENT",
                        "차지백 대상 결제를 찾을 수 없습니다: orderNo=" + orderNo));
        if (amount > approved) {
            throw new DisputeException("DISPUTE_AMOUNT_EXCEEDS",
                    "차지백 금액이 원 결제 금액을 초과합니다: 차지백 %d, 결제 %d".formatted(amount, approved));
        }
        Instant respondBy = Instant.now().plus(RESPOND_WINDOW);
        try {
            Dispute saved = repository.save(
                    Dispute.open(chargebackId, orderNo, paymentId, amount, reason, respondBy));
            log.info("분쟁 개시: chargebackId={} orderNo={} amount={}", chargebackId, orderNo, amount);
            return DisputeView.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 동시 수신으로 chargebackId UNIQUE 위반 → 멱등 처리(기존 분쟁 반환)
            return repository.findByChargebackId(chargebackId)
                    .map(DisputeView::from)
                    .orElseThrow(() -> e);
        }
    }

    /** 증빙 제출(OPEN → EVIDENCE_SUBMITTED). 상태 전이를 saveAndFlush로 확정한다. */
    public DisputeView submitEvidence(Long id, String memo) {
        Dispute dispute = require(id);
        dispute.submitEvidence(memo); // 도메인 가드: OPEN에서만
        // dirty-check 자동 flush는 readOnly 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인
        // 경우 신뢰할 수 없어(pay-26 교훈) 전이를 saveAndFlush로 확정한다.
        repository.saveAndFlush(dispute);
        return DisputeView.from(dispute);
    }

    /**
     * 승패 확정(OPEN/EVIDENCE_SUBMITTED → WON/LOST). 패소면 {@link DisputeLostEvent}를 발행해
     * 원장 역분개를 트리거한다. 상태 전이를 saveAndFlush로 먼저 확정한 뒤 발행한다.
     */
    public DisputeView resolve(Long id, boolean win) {
        Dispute dispute = require(id);
        dispute.resolve(win); // 도메인 가드: 최종 상태에서 재확정 불가
        repository.saveAndFlush(dispute);
        if (!win) {
            events.publishEvent(new DisputeLostEvent(
                    dispute.getOrderNo(), dispute.getPaymentId(), dispute.getAmount(), dispute.getId()));
            log.info("분쟁 패소 → 역분개 이벤트 발행: disputeId={} orderNo={} amount={}",
                    dispute.getId(), dispute.getOrderNo(), dispute.getAmount());
        }
        return DisputeView.from(dispute);
    }

    /** 최근 분쟁 목록(어드민 감사용). */
    @Transactional(readOnly = true)
    public List<DisputeView> recent() {
        return repository.findTop50ByOrderByIdDesc().stream()
                .map(DisputeView::from)
                .toList();
    }

    /** 분쟁 상세. */
    @Transactional(readOnly = true)
    public DisputeView detail(Long id) {
        return DisputeView.from(require(id));
    }

    private Dispute require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> DisputeException.notFound(id));
    }
}
