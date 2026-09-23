package com.beomsu.becommerce.receipt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashReceiptTest {

    @Test
    @DisplayName("발급 요청 → 완료 → 취소 상태 전이")
    void lifecycle() {
        CashReceipt r = CashReceipt.request("order-1", 10_000, "DEDUCTION");
        assertThat(r.getStatus()).isEqualTo(CashReceiptStatus.REQUESTED);

        r.markIssued("cr-1");
        assertThat(r.getStatus()).isEqualTo(CashReceiptStatus.ISSUED);
        assertThat(r.getReceiptKey()).isEqualTo("cr-1");

        r.cancel();
        assertThat(r.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
    }

    @Test
    @DisplayName("이미 취소된 건 취소는 멱등 무시")
    void cancelIdempotent() {
        CashReceipt r = CashReceipt.request("order-1", 10_000, "DEDUCTION");
        r.cancel();
        r.cancel(); // 예외 없이 통과
        assertThat(r.getStatus()).isEqualTo(CashReceiptStatus.CANCELED);
    }

    @Test
    @DisplayName("REQUESTED가 아닌 상태에서 발급은 예외")
    void issueFromWrongState() {
        CashReceipt r = CashReceipt.request("order-1", 10_000, "DEDUCTION");
        r.markIssued("cr-1");
        assertThatThrownBy(() -> r.markIssued("cr-2")).isInstanceOf(IllegalStateException.class);
    }
}
