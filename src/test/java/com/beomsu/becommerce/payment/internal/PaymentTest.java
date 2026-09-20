package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.internal.TriggeredBy;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private Payment approvedPayment() {
        Payment p = Payment.initiate("order-1", Money.krw(10_000));
        p.startApproval("pk-1");
        p.approve("CARD");
        return p;
    }

    @Test
    @DisplayName("승인 플로우: READY → IN_PROGRESS → DONE, 이력이 쌓인다")
    void approveFlow() {
        Payment p = approvedPayment();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(p.getMethod()).isEqualTo("CARD");
        assertThat(p.getApprovedAt()).isNotNull();
        assertThat(p.getHistories()).hasSize(2); // READY→IN_PROGRESS, IN_PROGRESS→DONE
    }

    @Test
    @DisplayName("부분취소: 잔액이 줄고 PARTIAL_CANCELED가 된다")
    void partialCancel() {
        Payment p = approvedPayment();

        p.cancel(Money.krw(3_000), TriggeredBy.USER, "부분 변심");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
        assertThat(p.balanceAsMoney()).isEqualTo(Money.krw(7_000));
    }

    @Test
    @DisplayName("잔액을 모두 취소하면 CANCELED가 된다")
    void fullCancelByPartials() {
        Payment p = approvedPayment();

        p.cancel(Money.krw(4_000), TriggeredBy.USER, "1차");
        p.cancel(Money.krw(6_000), TriggeredBy.USER, "2차");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(p.balanceAsMoney()).isEqualTo(Money.krw(0));
    }

    @Test
    @DisplayName("잔액을 초과하는 취소는 예외")
    void cancelExceedingBalance() {
        Payment p = approvedPayment();

        assertThatThrownBy(() -> p.cancel(Money.krw(11_000), TriggeredBy.USER, "과다"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code())
                        .isEqualTo("CANCEL_AMOUNT_EXCEEDED"));
    }

    @Test
    @DisplayName("타임아웃은 UNKNOWN으로 보존되고 사유가 남는다")
    void timeoutBecomesUnknown() {
        Payment p = Payment.initiate("order-2", Money.krw(5_000));
        p.startApproval("pk-2");

        p.markUnknown("PG 응답 타임아웃");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(p.getUnknownReason()).isEqualTo("PG 응답 타임아웃");
    }

    @Test
    @DisplayName("불법 전이 시도는 예외 (취소된 결제를 승인)")
    void illegalTransitionThrows() {
        Payment p = approvedPayment();
        p.cancel(Money.krw(10_000), TriggeredBy.USER, "전액");

        assertThatThrownBy(() -> p.approve("CARD"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
    }
}
