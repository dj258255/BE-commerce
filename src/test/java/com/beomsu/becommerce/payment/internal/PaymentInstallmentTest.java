package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 할부는 <b>우리가 받을 돈을 바꾸지 않는다</b>. 카드사가 가맹점에 일시로 지급하고 분납은
 * 카드사와 고객 사이의 일이다. 그래서 여기서 고정하는 것은 금액 계산이 아니라
 * <b>받아들일 값의 범위</b>다.
 *
 * <p>승인 요청을 내보낸 뒤 카드사가 거절하면 그때는 이미 우리 쪽에 IN_PROGRESS 행이 남는다.
 * 우리가 미리 알 수 있는 것은 미리 막는다.
 */
@DisplayName("할부 — 받아들일 값의 범위")
class PaymentInstallmentTest {

    @Test
    @DisplayName("일시불(0)이 기본이고, 금액이 얼마든 통과한다")
    void lumpSumIsDefault() {
        assertThat(Payment.initiate("ORD-1", Money.krw(1_000)).getInstallmentMonths()).isZero();
        assertThat(Payment.initiate("ORD-2", Money.krw(1_000), 0).getInstallmentMonths()).isZero();
    }

    @Test
    @DisplayName("5만원 이상이면 할부가 걸린다")
    void allowsInstallmentAtOrAboveThreshold() {
        assertThat(Payment.initiate("ORD-3", Money.krw(50_000), 3).getInstallmentMonths()).isEqualTo(3);
        assertThat(Payment.initiate("ORD-4", Money.krw(4_000_000), 12).getInstallmentMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("5만원 미만은 할부가 안 된다 — 국내 카드사 공통 조건")
    void rejectsInstallmentBelowThreshold() {
        assertThatThrownBy(() -> Payment.initiate("ORD-5", Money.krw(49_999), 3))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("할부가 되지 않습니다");
    }

    @Test
    @DisplayName("12개월을 넘거나 음수면 거절한다")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> Payment.initiate("ORD-6", Money.krw(1_000_000), 13))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("할부 개월");
        assertThatThrownBy(() -> Payment.initiate("ORD-7", Money.krw(1_000_000), -1))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("할부 개월");
    }

    @Test
    @DisplayName("할부여도 결제 금액과 잔액은 그대로다 — 분납은 카드사와 고객 사이의 일이다")
    void installmentDoesNotChangeAmount() {
        Payment p = Payment.initiate("ORD-8", Money.krw(1_200_000), 12);

        assertThat(p.getAmount()).isEqualTo(1_200_000);
        assertThat(p.getBalanceAmount()).isEqualTo(1_200_000);
    }
}
