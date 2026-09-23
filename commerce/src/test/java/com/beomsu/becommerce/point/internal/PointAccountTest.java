package com.beomsu.becommerce.point.internal;

import com.beomsu.becommerce.point.internal.PointException;
import com.beomsu.becommerce.point.internal.PointAccount;
import com.beomsu.becommerce.shared.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointAccountTest {

    @Test
    @DisplayName("use: 잔액에서 차감한다")
    void useDeducts() {
        PointAccount account = PointAccount.of(1L, 10_000);

        account.use(3_000);

        assertThat(account.getBalance()).isEqualTo(7_000);
    }

    @Test
    @DisplayName("use: 잔액보다 크면 INSUFFICIENT_POINT 예외 (마이너스 잔액 차단)")
    void useInsufficientThrows() {
        PointAccount account = PointAccount.of(1L, 2_000);

        assertThatThrownBy(() -> account.use(3_000))
                .isInstanceOf(PointException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("INSUFFICIENT_POINT"));
        assertThat(account.getBalance()).isEqualTo(2_000); // 변화 없음
    }

    @Test
    @DisplayName("restore: 차감분을 다시 더한다")
    void restoreAdds() {
        PointAccount account = PointAccount.of(1L, 4_000);
        account.use(3_000); // balance 1,000

        account.restore(3_000);

        assertThat(account.getBalance()).isEqualTo(4_000); // 원복
    }
}
