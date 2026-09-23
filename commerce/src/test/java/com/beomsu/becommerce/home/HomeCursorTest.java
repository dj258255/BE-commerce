package com.beomsu.becommerce.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HomeCursorTest {

    @Test
    @DisplayName("보여 준 상품과 행이 커서를 오가도 그대로다")
    void roundTrip() {
        HomeCursor next = HomeCursor.first().next(List.of(706016001L, 42L), List.of("recent", "for-you"));

        HomeCursor back = HomeCursor.decode(next.encode());

        assertThat(back.page()).isEqualTo(2);
        assertThat(back.shown()).containsExactlyInAnyOrder(706016001L, 42L);
        assertThat(back.usedRows()).containsExactlyInAnyOrder("recent", "for-you");
    }

    @Test
    @DisplayName("커서가 없으면 1쪽이다")
    void blankIsFirstPage() {
        assertThat(HomeCursor.decode(null).page()).isEqualTo(1);
        assertThat(HomeCursor.decode(" ").page()).isEqualTo(1);
    }

    @Test
    @DisplayName("모양이 틀리거나 너무 긴 커서는 거절한다 — API 가 400 으로 바꾼다")
    void rejectsTampered() {
        assertThatThrownBy(() -> HomeCursor.decode("!!!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HomeCursor.decode("x".repeat(HomeCursor.MAX_ENCODED_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        String page1 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("v1;1;;".getBytes());
        assertThatThrownBy(() -> HomeCursor.decode(page1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상품 24개(한 쪽 분량)가 실린 커서는 300자 안이다 — 실측 260자, 상품 하나에 약 10자")
    void sizePerPage() {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (long i = 0; i < 24; i++) {
            ids.add(700_000_000L + i * 1_013L);
        }
        String encoded = new HomeCursor(2, ids, Set.of("recent", "for-you", "popular")).encode();
        assertThat(encoded.length()).isLessThan(300);
    }
}
