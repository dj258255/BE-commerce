package com.beomsu.becommerce.receipt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceResolverTest {

    @Test
    @DisplayName("카드 → 매출전표 (세금계산서 중복 발행 안 함)")
    void card() {
        assertThat(EvidenceResolver.resolve("CARD", false)).isEqualTo(EvidenceType.SALES_SLIP);
    }

    @Test
    @DisplayName("가상계좌/이체(현금성) → 현금영수증")
    void cash() {
        assertThat(EvidenceResolver.resolve("VIRTUAL_ACCOUNT", false)).isEqualTo(EvidenceType.CASH_RECEIPT);
        assertThat(EvidenceResolver.resolve("TRANSFER", false)).isEqualTo(EvidenceType.CASH_RECEIPT);
    }

    @Test
    @DisplayName("B2B → 세금계산서")
    void b2b() {
        assertThat(EvidenceResolver.resolve("CARD", true)).isEqualTo(EvidenceType.TAX_INVOICE);
    }
}
