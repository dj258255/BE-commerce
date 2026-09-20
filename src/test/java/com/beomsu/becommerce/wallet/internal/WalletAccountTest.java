package com.beomsu.becommerce.wallet.internal;

import com.beomsu.becommerce.wallet.internal.WalletException;
import com.beomsu.becommerce.wallet.internal.WalletAccount;
import com.beomsu.becommerce.shared.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletAccountTest {

    @Test
    @DisplayName("charge: 잔액을 늘린다")
    void chargeAdds() {
        WalletAccount account = WalletAccount.of(1L);

        account.charge(500_000);

        assertThat(account.getBalance()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("charge: 한도(200만원)까지는 정상 충전")
    void chargeUpToLimit() {
        WalletAccount account = WalletAccount.of(1L);

        account.charge(WalletException.MAX_BALANCE); // 정확히 200만원

        assertThat(account.getBalance()).isEqualTo(WalletException.MAX_BALANCE);
    }

    @Test
    @DisplayName("charge: 충전 후 잔액이 전금법 기명 한도(200만원)를 넘으면 LIMIT_EXCEEDED (규제를 코드로 강제)")
    void chargeOverLimitThrows() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(1_500_000);

        assertThatThrownBy(() -> account.charge(600_000)) // 합 210만원 → 초과
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("LIMIT_EXCEEDED"));
        assertThat(account.getBalance()).isEqualTo(1_500_000); // 변화 없음
    }

    @Test
    @DisplayName("use: 잔액에서 차감한다")
    void useDeducts() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);

        account.use(3_000);

        assertThat(account.getBalance()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("use: 잔액보다 크면 INSUFFICIENT_BALANCE (마이너스 잔액 차단)")
    void useInsufficientThrows() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(2_000);

        assertThatThrownBy(() -> account.use(3_000))
                .isInstanceOf(WalletException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("INSUFFICIENT_BALANCE"));
        assertThat(account.getBalance()).isEqualTo(2_000); // 변화 없음
    }

    @Test
    @DisplayName("refund: 차감분을 되돌린다 (한도 검증 완화)")
    void refundAdds() {
        WalletAccount account = WalletAccount.of(1L);
        account.charge(10_000);
        account.use(4_000); // 6,000

        account.refund(4_000);

        assertThat(account.getBalance()).isEqualTo(10_000); // 원복
    }

    @Test
    @DisplayName("음수 금액은 모든 연산에서 IllegalArgumentException")
    void negativeAmountThrows() {
        WalletAccount account = WalletAccount.of(1L);

        assertThatThrownBy(() -> account.charge(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.use(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.refund(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
