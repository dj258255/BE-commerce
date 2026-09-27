package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 옛 생성자(3·4인자)는 시도별 키가 없어 주문번호를 그대로 멱등키로 쓴다(#402 이전 동작, 호환용). */
class PgApproveCommandTest {

    @Test
    @DisplayName("3인자 생성자는 orderNo를 idempotencyKey로 쓴다")
    void threeArgConstructorDefaultsKeyToOrderNo() {
        PgApproveCommand cmd = new PgApproveCommand("pk-1", "order-1", 10_000);
        assertThat(cmd.idempotencyKey()).isEqualTo("order-1");
        assertThat(cmd.installmentMonths()).isEqualTo(0);
    }

    @Test
    @DisplayName("4인자 생성자도 orderNo를 idempotencyKey로 쓴다")
    void fourArgConstructorDefaultsKeyToOrderNo() {
        PgApproveCommand cmd = new PgApproveCommand("pk-1", "order-1", 10_000, 3);
        assertThat(cmd.idempotencyKey()).isEqualTo("order-1");
        assertThat(cmd.installmentMonths()).isEqualTo(3);
    }

    @Test
    @DisplayName("5인자 생성자는 명시한 idempotencyKey를 그대로 쓴다")
    void fiveArgConstructorKeepsExplicitKey() {
        PgApproveCommand cmd = new PgApproveCommand("pk-1", "order-1", 10_000, 0, "order-1:7");
        assertThat(cmd.idempotencyKey()).isEqualTo("order-1:7");
    }
}
