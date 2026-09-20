package com.beomsu.becommerce.payment.forcecancel;

import com.beomsu.becommerce.payment.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForceCancelRequestTest {

    private ForceCancelRequest requested() {
        return ForceCancelRequest.request(10L, 5_000, "분쟁 정정", "admin");
    }

    @Test
    @DisplayName("생성 직후: REQUESTED, requestedBy 보존, approvedBy/resolvedAt=null")
    void newRequestIsRequested() {
        ForceCancelRequest req = requested();

        assertThat(req.getStatus()).isEqualTo(ForceCancelStatus.REQUESTED);
        assertThat(req.getPaymentId()).isEqualTo(10L);
        assertThat(req.getCancelAmount()).isEqualTo(5_000);
        assertThat(req.getReason()).isEqualTo("분쟁 정정");
        assertThat(req.getRequestedBy()).isEqualTo("admin");
        assertThat(req.getApprovedBy()).isNull();
        assertThat(req.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("approve(다른 승인자): REQUESTED → EXECUTED, approvedBy·resolvedAt 기록")
    void approveByOtherExecutes() {
        ForceCancelRequest req = requested();

        req.approve("admin2");

        assertThat(req.getStatus()).isEqualTo(ForceCancelStatus.EXECUTED);
        assertThat(req.getApprovedBy()).isEqualTo("admin2");
        assertThat(req.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("가드: 요청자 본인이 승인하면 MAKER_CHECKER_VIOLATION, 상태 불변")
    void approveBySelfRejected() {
        ForceCancelRequest req = requested();

        assertThatThrownBy(() -> req.approve("admin"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("MAKER_CHECKER_VIOLATION"));

        assertThat(req.getStatus()).isEqualTo(ForceCancelStatus.REQUESTED);
        assertThat(req.getApprovedBy()).isNull();
    }

    @Test
    @DisplayName("가드: 이미 EXECUTED면 다시 approve 불가 → INVALID_STATE_TRANSITION")
    void approveAfterExecutedRejected() {
        ForceCancelRequest req = requested();
        req.approve("admin2");

        assertThatThrownBy(() -> req.approve("admin3"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("reject: REQUESTED → REJECTED, approvedBy·resolvedAt 기록")
    void rejectTransitions() {
        ForceCancelRequest req = requested();

        req.reject("admin2");

        assertThat(req.getStatus()).isEqualTo(ForceCancelStatus.REJECTED);
        assertThat(req.getApprovedBy()).isEqualTo("admin2");
        assertThat(req.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("가드: 이미 REJECTED면 approve 불가 → INVALID_STATE_TRANSITION")
    void approveAfterRejectedRejected() {
        ForceCancelRequest req = requested();
        req.reject("admin2");

        assertThatThrownBy(() -> req.approve("admin2"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));
    }
}
