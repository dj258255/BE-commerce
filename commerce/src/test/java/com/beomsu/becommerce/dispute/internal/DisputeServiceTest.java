package com.beomsu.becommerce.dispute.internal;

import com.beomsu.becommerce.dispute.DisputeLostEvent;
import com.beomsu.becommerce.dispute.internal.DisputeView;
import com.beomsu.becommerce.dispute.internal.DisputeService;
import com.beomsu.becommerce.dispute.internal.DisputeRepository;
import com.beomsu.becommerce.dispute.internal.DisputeException;
import com.beomsu.becommerce.dispute.internal.Dispute;
import com.beomsu.becommerce.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DisputeServiceTest {

    private DisputeRepository repository;
    private PaymentService paymentService;
    private ApplicationEventPublisher events;
    private DisputeService service;

    @BeforeEach
    void setUp() {
        repository = mock(DisputeRepository.class);
        paymentService = mock(PaymentService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new DisputeService(repository, paymentService, events);
        // 기본: 원 결제 10,000원이 존재한다고 본다(개별 테스트에서 재정의).
        when(paymentService.approvedAmountByOrderNo(anyString())).thenReturn(Optional.of(10_000L));
    }

    private Dispute openDispute(Long id) {
        Dispute d = Dispute.open("cb-1", "order-1", 100L, 10_000, "fraudulent",
                Instant.now().plus(7, ChronoUnit.DAYS));
        if (id != null) {
            ReflectionTestUtils.setField(d, "id", id);
        }
        return d;
    }

    @Test
    @DisplayName("openFromChargeback: 신규 차지백이면 OPEN 분쟁을 생성한다")
    void openCreatesNewDispute() {
        when(repository.findByChargebackId("cb-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DisputeView view = service.openFromChargeback("cb-1", "order-1", 100L, 10_000, "fraudulent");

        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.chargebackId()).isEqualTo("cb-1");
        assertThat(view.amount()).isEqualTo(10_000);
        verify(repository).save(any(Dispute.class));
    }

    @Test
    @DisplayName("openFromChargeback: 같은 chargebackId 재수신은 기존 분쟁 반환(멱등) — 저장 없음")
    void openIsIdempotentOnDuplicateChargeback() {
        Dispute existing = openDispute(7L);
        when(repository.findByChargebackId("cb-1")).thenReturn(Optional.of(existing));

        DisputeView view = service.openFromChargeback("cb-1", "order-1", 100L, 10_000, "fraudulent");

        assertThat(view.id()).isEqualTo(7L);
        assertThat(view.status()).isEqualTo("OPEN");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("openFromChargeback: 원 결제가 없는 orderNo는 DISPUTE_NO_PAYMENT — 분쟁 미생성(가짜 차지백 차단)")
    void openRejectsChargebackForUnknownPayment() {
        when(repository.findByChargebackId("cb-x")).thenReturn(Optional.empty());
        when(paymentService.approvedAmountByOrderNo("order-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openFromChargeback("cb-x", "order-x", null, 10_000, "fraudulent"))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("DISPUTE_NO_PAYMENT"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("openFromChargeback: 차지백 금액이 원 결제 금액 초과면 DISPUTE_AMOUNT_EXCEEDS — 원장 오염 차단")
    void openRejectsInflatedChargeback() {
        when(repository.findByChargebackId("cb-big")).thenReturn(Optional.empty());
        when(paymentService.approvedAmountByOrderNo("order-1")).thenReturn(Optional.of(10_000L));

        assertThatThrownBy(() -> service.openFromChargeback("cb-big", "order-1", 100L, 10_000_000, "fraudulent"))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("DISPUTE_AMOUNT_EXCEEDS"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("submitEvidence: OPEN → EVIDENCE_SUBMITTED, saveAndFlush로 확정")
    void submitEvidenceTransitions() {
        Dispute d = openDispute(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(d));

        DisputeView view = service.submitEvidence(5L, "증빙 메모");

        assertThat(view.status()).isEqualTo("EVIDENCE_SUBMITTED");
        assertThat(d.getEvidenceMemo()).isEqualTo("증빙 메모");
        verify(repository).saveAndFlush(d);
    }

    @Test
    @DisplayName("resolve(WON): WON 확정, 패소 이벤트는 발행하지 않는다")
    void resolveWonPublishesNothing() {
        Dispute d = openDispute(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(d));

        DisputeView view = service.resolve(5L, true);

        assertThat(view.status()).isEqualTo("WON");
        verify(repository).saveAndFlush(d);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("resolve(LOST): LOST 확정 + DisputeLostEvent 발행(역분개 트리거)")
    void resolveLostPublishesEvent() {
        Dispute d = openDispute(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(d));

        DisputeView view = service.resolve(5L, false);

        assertThat(view.status()).isEqualTo("LOST");
        verify(repository).saveAndFlush(d);
        ArgumentCaptor<DisputeLostEvent> captor = ArgumentCaptor.forClass(DisputeLostEvent.class);
        verify(events).publishEvent(captor.capture());
        DisputeLostEvent event = captor.getValue();
        assertThat(event.disputeId()).isEqualTo(5L);
        assertThat(event.orderNo()).isEqualTo("order-1");
        assertThat(event.paymentId()).isEqualTo(100L);
        assertThat(event.amount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("submitEvidence: 잘못된 전이(이미 확정된 분쟁)는 INVALID_DISPUTE_TRANSITION")
    void submitEvidenceInvalidTransition() {
        Dispute d = openDispute(5L);
        d.resolve(true); // 이미 WON
        when(repository.findById(5L)).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service.submitEvidence(5L, "뒤늦은 증빙"))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("INVALID_DISPUTE_TRANSITION"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("없는 분쟁 조회/변경은 DISPUTE_NOT_FOUND")
    void notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(999L))
                .isInstanceOf(DisputeException.class)
                .satisfies(e -> assertThat(((DisputeException) e).code()).isEqualTo("DISPUTE_NOT_FOUND"));
    }
}
