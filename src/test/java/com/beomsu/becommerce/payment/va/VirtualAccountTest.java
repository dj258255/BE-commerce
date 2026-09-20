package com.beomsu.becommerce.payment.va;

import com.beomsu.becommerce.payment.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualAccountTest {

    private VirtualAccount issued() {
        return VirtualAccount.issue("order-1", "pk-1", "20", "12345678901234",
                10_000L, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("issue 시 WAITING_FOR_DEPOSIT 상태로 생성된다")
    void issueStartsWaiting() {
        VirtualAccount va = issued();
        assertThat(va.getStatus()).isEqualTo(VaStatus.WAITING_FOR_DEPOSIT);
        assertThat(va.getDepositedAt()).isNull();
        assertThat(va.getAmount()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("confirmDeposit → DONE, 입금 시각 기록")
    void confirmDeposit() {
        VirtualAccount va = issued();
        va.confirmDeposit();
        assertThat(va.getStatus()).isEqualTo(VaStatus.DONE);
        assertThat(va.getDepositedAt()).isNotNull();
    }

    @Test
    @DisplayName("reverseDeposit → WAITING_FOR_DEPOSIT, 입금 시각 초기화(은행 지연 통보)")
    void reverseDeposit() {
        VirtualAccount va = issued();
        va.confirmDeposit();
        va.reverseDeposit("은행 입금 실패 지연 통보");
        assertThat(va.getStatus()).isEqualTo(VaStatus.WAITING_FOR_DEPOSIT);
        assertThat(va.getDepositedAt()).isNull();
    }

    @Test
    @DisplayName("expire → EXPIRED")
    void expire() {
        VirtualAccount va = issued();
        va.expire();
        assertThat(va.getStatus()).isEqualTo(VaStatus.EXPIRED);
    }

    @Test
    @DisplayName("입금된 건(DONE)은 만료할 수 없다 — 불법 전이 예외")
    void illegalTransitionThrows() {
        VirtualAccount va = issued();
        va.confirmDeposit();
        assertThatThrownBy(va::expire)
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("허용되지 않은");
    }
}
