package com.beomsu.becommerce.payment.forcecancel;

import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ForceCancelServiceTest {

    private ForceCancelRequestRepository repository;
    private PaymentRepository paymentRepository;
    private PaymentService paymentService;
    private ForceCancelService service;

    @BeforeEach
    void setUp() {
        repository = mock(ForceCancelRequestRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentService = mock(PaymentService.class);
        service = new ForceCancelService(repository, paymentRepository, paymentService);
    }

    private Payment payment() {
        return Payment.initiate("ord-1", Money.krw(10_000));
    }

    private ForceCancelRequest requestedBy(String requester) {
        return ForceCancelRequest.request(10L, 5_000, "분쟁 정정", requester);
    }

    // ---- request ----

    @Test
    @DisplayName("request: 결제 존재·취소액>0면 REQUESTED로 저장하고 뷰 반환")
    void requestSavesRequested() {
        when(paymentRepository.findById(10L)).thenReturn(Optional.of(payment()));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ForceCancelView view = service.request(10L, 5_000, "분쟁 정정", "admin");

        ArgumentCaptor<ForceCancelRequest> captor = ArgumentCaptor.forClass(ForceCancelRequest.class);
        verify(repository).save(captor.capture());
        ForceCancelRequest saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ForceCancelStatus.REQUESTED);
        assertThat(saved.getRequestedBy()).isEqualTo("admin");
        assertThat(view.status()).isEqualTo("REQUESTED");
        assertThat(view.paymentId()).isEqualTo(10L);
        // 요청 단계에서는 실제 취소가 일어나지 않는다.
        verify(paymentService, never()).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("request: 없는 결제면 PAYMENT_NOT_FOUND, 저장 안 함")
    void requestUnknownPayment() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(99L, 5_000, "x", "admin"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_NOT_FOUND"));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("request: 취소액<=0이면 INVALID_CANCEL_AMOUNT")
    void requestNonPositiveAmount() {
        assertThatThrownBy(() -> service.request(10L, 0, "x", "admin"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("INVALID_CANCEL_AMOUNT"));

        verify(paymentRepository, never()).findById(anyLong());
        verify(repository, never()).save(any());
    }

    // ---- approve (= 실행) ----

    @Test
    @DisplayName("approve(다른 승인자): EXECUTED + PaymentService.cancel 실행 + saveAndFlush")
    void approveByOtherExecutesCancel() {
        ForceCancelRequest req = requestedBy("admin");
        when(repository.findById(1L)).thenReturn(Optional.of(req));

        ForceCancelView view = service.approve(1L, "admin2");

        assertThat(view.status()).isEqualTo("EXECUTED");
        assertThat(view.approvedBy()).isEqualTo("admin2");
        // 승인=실행: 기존 PaymentService.cancel을 재사용해 실제 취소한다.
        verify(paymentService).cancel(eq(10L), eq(Money.krw(5_000)), eq("분쟁 정정"));
        verify(repository).saveAndFlush(req);
    }

    @Test
    @DisplayName("approve(요청자 본인): MAKER_CHECKER_VIOLATION + cancel 미호출(요청자≠승인자 강제)")
    void approveBySelfViolatesMakerChecker() {
        ForceCancelRequest req = requestedBy("admin");
        when(repository.findById(1L)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(1L, "admin"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("MAKER_CHECKER_VIOLATION"));

        verify(paymentService, never()).cancel(any(), any(), any());
        verify(repository, never()).saveAndFlush(any());
        assertThat(req.getStatus()).isEqualTo(ForceCancelStatus.REQUESTED);
    }

    @Test
    @DisplayName("approve: 이미 EXECUTED 건이면 상태 가드 예외 + cancel 미호출")
    void approveAlreadyExecuted() {
        ForceCancelRequest req = requestedBy("admin");
        req.approve("admin2"); // 이미 실행됨
        when(repository.findById(1L)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.approve(1L, "admin3"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));

        verify(paymentService, never()).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("approve: 없는 요청이면 FORCE_CANCEL_NOT_FOUND")
    void approveUnknownRequest() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(99L, "admin2"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("FORCE_CANCEL_NOT_FOUND"));

        verify(paymentService, never()).cancel(any(), any(), any());
    }

    // ---- reject ----

    @Test
    @DisplayName("reject: REJECTED로 전이 + saveAndFlush, cancel 미호출")
    void rejectTransitions() {
        ForceCancelRequest req = requestedBy("admin");
        when(repository.findById(1L)).thenReturn(Optional.of(req));

        ForceCancelView view = service.reject(1L, "admin2");

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.approvedBy()).isEqualTo("admin2");
        verify(repository).saveAndFlush(req);
        verify(paymentService, never()).cancel(any(), any(), any());
    }

    @Test
    @DisplayName("reject: 이미 REJECTED 건이면 상태 가드 예외")
    void rejectAlreadyRejected() {
        ForceCancelRequest req = requestedBy("admin");
        req.reject("admin2");
        when(repository.findById(1L)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.reject(1L, "admin3"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    // ---- list ----

    @Test
    @DisplayName("list: 상태별 요청 페이지를 뷰로 매핑")
    void listMapsViews() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByStatus(eq(ForceCancelStatus.REQUESTED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(requestedBy("admin")), pageable, 1));

        Page<ForceCancelView> views = service.list(ForceCancelStatus.REQUESTED, pageable);

        assertThat(views).hasSize(1);
        assertThat(views.getContent().get(0).status()).isEqualTo("REQUESTED");
    }
}
