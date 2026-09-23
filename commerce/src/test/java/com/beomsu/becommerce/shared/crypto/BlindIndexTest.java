package com.beomsu.becommerce.shared.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlindIndexTest {

    @Test
    @DisplayName("같은 평문은 같은 해시 (동등 검색 가능)")
    void deterministic() {
        assertThat(BlindIndex.hash("110-1234-567890", "blind-index-secret-key"))
                .isEqualTo(BlindIndex.hash("110-1234-567890", "blind-index-secret-key"));
    }

    @Test
    @DisplayName("다른 평문은 다른 해시")
    void differs() {
        assertThat(BlindIndex.hash("a", "blind-index-secret-key")).isNotEqualTo(BlindIndex.hash("b", "blind-index-secret-key"));
    }
}
