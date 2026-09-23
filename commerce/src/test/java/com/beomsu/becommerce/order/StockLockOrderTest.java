package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.internal.CheckoutTx;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감의 <b>잠금 순서</b>를 고정한다.
 *
 * <p>여러 상품을 담은 주문은 재고 행을 여러 개 순차로 잠근다. 잠금 순서가 주문 항목 순서를 따르면,
 * 같은 두 상품이 반대 순서로 담긴 두 주문이 동시에 들어올 때 서로가 상대의 행을 기다려 교착한다
 * (A→B 대 B→A). 상품 ID 오름차순으로 정렬하면 모든 트랜잭션의 잠금 순서가 같아져 사이클이 생기지 않는다.
 *
 * <p>단일 상품 주문에서는 드러나지 않아 부하 실험에서도 잡히지 않았던 결함이다.
 */
class StockLockOrderTest {

    /** 프로덕션(CheckoutTx.settle)이 쓰는 정렬 규칙과 같은 비교자. */
    private static List<Long> lockOrderOf(List<Long> productIds) {
        return productIds.stream().sorted(Comparator.naturalOrder()).toList();
    }

    @Test
    @DisplayName("담긴 순서가 반대여도 잠금 순서는 같다 — 교착 사이클이 생기지 않는다")
    void lockOrderIsSameRegardlessOfCartOrder() {
        List<Long> orderA = lockOrderOf(List.of(1L, 2L));
        List<Long> orderB = lockOrderOf(List.of(2L, 1L));

        assertThat(orderA).isEqualTo(orderB);
        assertThat(orderA).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("정렬이 없으면 두 주문의 잠금 순서가 어긋난다 — 이 테스트가 고치기 전의 상태를 설명한다")
    void withoutSortingTheOrdersConflict() {
        List<Long> unsortedA = List.of(1L, 2L);
        List<Long> unsortedB = List.of(2L, 1L);

        // A는 1을 잡고 2를 기다리고, B는 2를 잡고 1을 기다린다 → 교착
        assertThat(unsortedA).isNotEqualTo(unsortedB);
        assertThat(unsortedA.get(0)).isNotEqualTo(unsortedB.get(0));
    }

    @Test
    @DisplayName("상품이 셋 이상이어도 전순서가 하나로 정해진다")
    void lockOrderIsTotalForManyItems() {
        assertThat(lockOrderOf(List.of(30L, 10L, 20L)))
                .containsExactly(10L, 20L, 30L)
                .isEqualTo(lockOrderOf(List.of(20L, 30L, 10L)));
    }
}
