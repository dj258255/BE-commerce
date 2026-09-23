package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.TriggeredBy;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.internal.PaymentHistoryView;
import com.beomsu.becommerce.payment.internal.PaymentCancelTx;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.StuckPaymentInfo;
import com.beomsu.becommerce.payment.pg.FakePgClient;
import com.beomsu.becommerce.payment.pg.PgApproveResult;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgPaymentStatus;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {

    private PaymentRepository repository;
    private ApplicationEventPublisher events;
    private FakePgClient pg;
    private SimpleMeterRegistry meterRegistry;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        events = mock(ApplicationEventPublisher.class);
        pg = new FakePgClient();
        meterRegistry = new SimpleMeterRegistry();
        when(repository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PaymentService(repository, pg, new PaymentCancelTx(repository, events), events, meterRegistry);
    }

    /** IN_PROGRESS(예약 완료) 상태의 결제를 id를 심어 만든다 — applyResult 테스트용. */
    private Payment inProgress(String orderNo, String paymentKey, long amount, long id) {
        Payment p = Payment.initiate(orderNo, Money.krw(amount));
        p.startApproval(paymentKey); // READY → IN_PROGRESS
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    @Test
    @DisplayName("beginApproval(예약, Phase 1): 결제를 IN_PROGRESS로 적재한다 — PG 콜 전에")
    void beginApprovalPersistsInProgress() {
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);

        service.beginApproval("order-0", "pk-0", Money.krw(10_000));

        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        assertThat(saved.getValue().getOrderNo()).isEqualTo("order-0");
    }

    @Test
    @DisplayName("pgApprove(Phase 2, tx 밖): PG 결과를 ApprovalOutcome으로 매핑 + 성공 메트릭 증가")
    void pgApproveMapsOutcomeAndCountsMetric() {
        pg.setNextResult(PgApproveResult.success("CARD"));

        ApprovalOutcome outcome = service.pgApprove("order-1", "pk-1", Money.krw(10_000));

        assertThat(outcome.result()).isEqualTo(ApprovalOutcome.Result.SUCCESS);
        assertThat(outcome.method()).isEqualTo("CARD");
        // 관측성: 결과별 카운터가 증가한다 (Grafana 결제 성공률의 소스)
        assertThat(meterRegistry.counter("payment.confirm", "outcome", "success").count()).isEqualTo(1.0);
        // PG 콜은 DB를 건드리지 않는다(트랜잭션 밖).
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("applyResult(Phase 3) 성공: DONE + PaymentConfirmedEvent 발행 + saveAndFlush")
    void applyResultSuccessMarksDoneAndPublishes() {
        Payment p = inProgress("order-1", "pk-1", 10_000, 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        ConfirmResult result = service.applyResult(1L, new ApprovalOutcome(ApprovalOutcome.Result.SUCCESS, "CARD", null));

        assertThat(result.isApproved()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(result.method()).isEqualTo("CARD");
        verify(events).publishEvent(any(PaymentConfirmedEvent.class));
        verify(repository, atLeastOnce()).saveAndFlush(p);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("applyResult 타임아웃: UNKNOWN + 이벤트 미발행 (실패로 단정하지 않음)")
    void applyResultTimeoutMarksUnknownNoEvent() {
        Payment p = inProgress("order-2", "pk-2", 10_000, 2L);
        when(repository.findById(2L)).thenReturn(Optional.of(p));

        ConfirmResult result = service.applyResult(2L, new ApprovalOutcome(ApprovalOutcome.Result.TIMEOUT, null, "PG 응답 없음"));

        assertThat(result.isUnknown()).isTrue();
        assertThat(result.status()).isEqualTo(PaymentStatus.UNKNOWN);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("applyResult 거절: ABORTED + 이벤트 미발행")
    void applyResultFailedMarksAborted() {
        Payment p = inProgress("order-3", "pk-3", 10_000, 3L);
        when(repository.findById(3L)).thenReturn(Optional.of(p));

        ConfirmResult result = service.applyResult(3L, new ApprovalOutcome(ApprovalOutcome.Result.FAILED, null, "잔액부족"));

        assertThat(result.status()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(result.message()).isEqualTo("잔액부족");
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("applyResult 멱등: 이미 확정된(DONE) 결제는 재실행해도 전이하지 않고 현재 상태를 반환")
    void applyResultIdempotentOnAlreadyResolved() {
        Payment p = inProgress("order-9", "pk-9", 10_000, 9L);
        p.approve("CARD"); // 이미 DONE
        when(repository.findById(9L)).thenReturn(Optional.of(p));

        ConfirmResult result = service.applyResult(9L, new ApprovalOutcome(ApprovalOutcome.Result.SUCCESS, "CARD", null));

        assertThat(result.status()).isEqualTo(PaymentStatus.DONE);
        verify(events, never()).publishEvent(any()); // 재발행 안 함
        verify(repository, never()).saveAndFlush(any()); // 재전이 없음
    }

    @Test
    @DisplayName("resolveStuckPayment: UNKNOWN 결제도 PG 재조회 — APPROVED면 DONE 확정 + SUCCESS 매핑")
    void resolveStuckPaymentRequeriesUnknownToSuccess() {
        PgClient mockPg = mock(PgClient.class);
        PaymentService svc = new PaymentService(repository, mockPg, new PaymentCancelTx(repository, events), events, meterRegistry);
        Payment stuck = inProgress("order-r", "pk-r", 8_000, 5L);
        stuck.markUnknown("타임아웃"); // IN_PROGRESS → UNKNOWN
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-r")).thenReturn(Optional.of(stuck));
        when(mockPg.query("pk-r", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.APPROVED, "CARD"));

        Optional<StuckPaymentInfo> info = svc.resolveStuckPayment("order-r");

        assertThat(info).isPresent();
        assertThat(info.get().amount()).isEqualTo(8_000);
        assertThat(info.get().outcome().result()).isEqualTo(ApprovalOutcome.Result.SUCCESS);
        assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.DONE);
        verify(events).publishEvent(any(PaymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("resolveStuckPayment: PG가 CANCELED면 ABORTED로 확정 — IN_PROGRESS→CANCELED 불법 전이 회피")
    void resolveStuckPaymentCanceledMapsToAborted() {
        PgClient mockPg = mock(PgClient.class);
        PaymentService svc = new PaymentService(repository, mockPg, new PaymentCancelTx(repository, events), events, meterRegistry);
        Payment stuck = inProgress("order-c", "pk-c", 8_000, 6L); // IN_PROGRESS
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-c")).thenReturn(Optional.of(stuck));
        when(mockPg.query("pk-c", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.CANCELED, null));

        Optional<StuckPaymentInfo> info = svc.resolveStuckPayment("order-c");

        // IN_PROGRESS→CANCELED는 전이표상 불법 → abortByRecovery로 ABORTED 확정(예외 없이).
        assertThat(info).isPresent();
        assertThat(info.get().outcome().result()).isEqualTo(ApprovalOutcome.Result.FAILED);
        assertThat(stuck.getStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("resolveStuckPayment: 카드 결제 없으면(전액 포인트) empty")
    void resolveStuckPaymentEmptyForFullPoint() {
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-p")).thenReturn(Optional.empty());
        assertThat(service.resolveStuckPayment("order-p")).isEmpty();
    }

    @Test
    @DisplayName("부분취소 시 PaymentCanceledEvent 발행 (fullyCanceled=false)")
    void cancelPartial() {
        Payment done = Payment.initiate("order-4", Money.krw(10_000));
        done.startApproval("pk-4");
        done.approve("CARD");
        when(repository.findById(1L)).thenReturn(Optional.of(done));
        // 취소 사가 3단계는 PG 취소에 쓴 paymentKey로 결제를 다시 집는다.
        when(repository.findByPaymentKey("pk-4")).thenReturn(Optional.of(done));

        service.cancel(1L, Money.krw(3_000), "부분 변심");

        assertThat(done.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        // 취소 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(done);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(PaymentCanceledEvent.class, ce -> {
            assertThat(ce.fullyCanceled()).isFalse();
            assertThat(ce.cancelAmount()).isEqualTo(3_000);
        });
    }

    @Test
    @DisplayName("주문번호 취소 시 취소 상태 전이가 명시 saveAndFlush로 영속된다")
    void cancelByOrderNoPersistsWithSave() {
        Payment done = Payment.initiate("order-5", Money.krw(10_000));
        done.startApproval("pk-5");
        done.approve("CARD");
        when(repository.findFirstByOrderNoAndStatusIn(any(), any()))
                .thenReturn(Optional.of(done));
        when(repository.findByPaymentKey("pk-5")).thenReturn(Optional.of(done));

        service.cancelByOrderNo("order-5", Money.krw(10_000), "전액 변심");

        assertThat(done.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(repository).saveAndFlush(done);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(PaymentCanceledEvent.class,
                ce -> assertThat(ce.fullyCanceled()).isTrue());
    }

    // --- 조회(read-only) 지원 ---

    @Test
    @DisplayName("orderNoOf: 결제의 주문번호 반환 / 없으면 empty")
    void orderNoOf() {
        Payment done = Payment.initiate("order-6", Money.krw(10_000));
        when(repository.findById(1L)).thenReturn(Optional.of(done));
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThat(service.orderNoOf(1L)).contains("order-6");
        assertThat(service.orderNoOf(2L)).isEmpty();
    }

    @Test
    @DisplayName("getDetail: 상태/이력 매핑 + 취소 전이만 cancels로 투영")
    void getDetailMapsHistoryAndCancels() {
        Payment payment = Payment.initiate("order-7", Money.krw(10_000));
        payment.startApproval("pk-7");        // READY → IN_PROGRESS
        payment.approve("CARD");              // IN_PROGRESS → DONE
        payment.cancel(Money.krw(3_000), TriggeredBy.USER, "부분 변심"); // DONE → PARTIAL_CANCELED
        // id는 DB 생성분(persist된 엔티티)이라 단위 테스트에선 직접 심는다.
        org.springframework.test.util.ReflectionTestUtils.setField(payment, "id", 1L);
        when(repository.findById(1L)).thenReturn(Optional.of(payment));

        PaymentDetailView view = service.getDetail(1L).orElseThrow();

        assertThat(view.orderNo()).isEqualTo("order-7");
        assertThat(view.status()).isEqualTo("PARTIAL_CANCELED");
        assertThat(view.amount()).isEqualTo(10_000);
        assertThat(view.balanceAmount()).isEqualTo(7_000);
        // history: 세 번의 전이가 모두 실린다.
        assertThat(view.history()).extracting(PaymentHistoryView::to)
                .containsExactly("IN_PROGRESS", "DONE", "PARTIAL_CANCELED");
        // cancels: 취소 전이(→ PARTIAL_CANCELED)만 투영된다.
        assertThat(view.cancels()).singleElement().satisfies(c -> {
            assertThat(c.to()).isEqualTo("PARTIAL_CANCELED");
            assertThat(c.reason()).isEqualTo("부분 변심");
        });
    }

    @Test
    @DisplayName("getDetail: 없는 결제는 empty")
    void getDetailEmpty() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.getDetail(9L)).isEmpty();
    }

    @Test
    @DisplayName("paymentStatusByOrderNo: 최신 결제 상태 반환 / 결제 없으면 empty")
    void paymentStatusByOrderNo() {
        Payment done = Payment.initiate("order-8", Money.krw(10_000));
        done.startApproval("pk-8");
        done.approve("CARD");
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-8"))
                .thenReturn(Optional.of(done));
        when(repository.findFirstByOrderNoOrderByRequestedAtDesc("order-none"))
                .thenReturn(Optional.empty());

        assertThat(service.paymentStatusByOrderNo("order-8")).contains("DONE");
        assertThat(service.paymentStatusByOrderNo("order-none")).isEmpty();
    }
}
