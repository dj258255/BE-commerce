package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 여러 대일 때 인스턴스 하나의 PG 동시 호출 상한(#387). */
class PgPerInstanceLimitTest {

    @Test
    @DisplayName("PG 계약 한도를 모르면 워커 보호 상한 그대로")
    void unknownMerchantLimitKeepsWorkerCap() {
        assertThat(ResilientPgClient.perInstanceLimit(40, 0, 3)).isEqualTo(40);
    }

    @Test
    @DisplayName("계약 한도를 대수로 나눈 몫과 워커 보호 상한 중 작은 쪽")
    void splitsMerchantLimitByInstances() {
        assertThat(ResilientPgClient.perInstanceLimit(40, 60, 3)).isEqualTo(20);
        assertThat(ResilientPgClient.perInstanceLimit(40, 60, 1)).isEqualTo(40);   // 한 대면 워커 보호가 더 작다
        assertThat(ResilientPgClient.perInstanceLimit(40, 60, 2)).isEqualTo(30);
    }

    @Test
    @DisplayName("대수가 한도보다 많아도 0 이 되지 않고 워커 보호가 없으면 몫만 쓴다")
    void neverZeroAndNoWorkerCap() {
        assertThat(ResilientPgClient.perInstanceLimit(40, 2, 5)).isEqualTo(1);
        assertThat(ResilientPgClient.perInstanceLimit(0, 60, 3)).isEqualTo(20);
        assertThat(ResilientPgClient.perInstanceLimit(40, 60, 0)).isEqualTo(40);
    }
}
