package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.RefundAllocator;
import com.beomsu.becommerce.order.internal.RefundAllocator.RefundAllocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefundAllocatorTest {

    @Test
    @DisplayName("취소액 ≤ 포인트결제분: 전액 포인트에서 환불 (카드 0)")
    void refundsFromPointFirst() {
        // 결제: 포인트 3,000 + 카드 7,000. 취소 2,000 → 전부 포인트에서.
        RefundAllocation a = RefundAllocator.allocate(2_000, 3_000, 0, 7_000);

        assertThat(a.fromPoint()).isEqualTo(2_000);
        assertThat(a.fromCard()).isEqualTo(0);
        assertThat(a.fromPoint() + a.fromCard()).isEqualTo(2_000); // 불변식
    }

    @Test
    @DisplayName("취소액 > 포인트결제분: 포인트를 먼저 소진하고 나머지를 카드에서 (어뷰징 방지)")
    void refundsPointThenCard() {
        // 결제: 포인트 3,000 + 카드 7,000. 취소 5,000 → 포인트 3,000 + 카드 2,000.
        RefundAllocation a = RefundAllocator.allocate(5_000, 3_000, 0, 7_000);

        assertThat(a.fromPoint()).isEqualTo(3_000); // 포인트 우선 전액
        assertThat(a.fromCard()).isEqualTo(2_000);
        assertThat(a.fromPoint() + a.fromCard()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("전액 취소: 포인트 전액 + 카드 전액")
    void refundsFullAmount() {
        RefundAllocation a = RefundAllocator.allocate(10_000, 3_000, 0, 7_000);

        assertThat(a.fromPoint()).isEqualTo(3_000);
        assertThat(a.fromCard()).isEqualTo(7_000);
        assertThat(a.fromPoint()).isLessThanOrEqualTo(3_000); // 포인트 결제분 이하
        assertThat(a.fromCard()).isLessThanOrEqualTo(7_000);  // 카드 결제분 이하
    }

    @Test
    @DisplayName("포인트→월렛→카드 순 배분: 포인트 소진 후 월렛, 그다음 카드")
    void refundsPointThenWalletThenCard() {
        // 결제: 포인트 2,000 + 월렛 3,000 + 카드 5,000 = 10,000. 취소 7,000 → 포인트 2,000 + 월렛 3,000 + 카드 2,000.
        RefundAllocation a = RefundAllocator.allocate(7_000, 2_000, 3_000, 5_000);

        assertThat(a.fromPoint()).isEqualTo(2_000);   // 포인트 먼저 전액
        assertThat(a.fromWallet()).isEqualTo(3_000);  // 그다음 월렛 전액
        assertThat(a.fromCard()).isEqualTo(2_000);    // 나머지 카드
        assertThat(a.fromPoint() + a.fromWallet() + a.fromCard()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("취소액 ≤ 월렛분(카드 결제 없음): 카드 취소 0으로 월렛에서 환불")
    void refundsFromWalletWhenNoCard() {
        // 결제: 카드 0 + 월렛 6,000. 취소 6,000 → 전부 월렛에서, 카드 취소 없음.
        RefundAllocation a = RefundAllocator.allocate(6_000, 0, 6_000, 0);

        assertThat(a.fromWallet()).isEqualTo(6_000);
        assertThat(a.fromCard()).isZero();
        assertThat(a.fromPoint()).isZero();
    }
}
